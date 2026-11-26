import type {
  AuthTokens,
  MatchmakingStatus,
  MatchmakingTicket,
  PlayerMatch,
  PlayerProfile,
  RecoverResponse,
  RegisterResponse,
} from './types';

const STORAGE_KEY = 'ponggame.session.tokens';
import { tracedFetch } from './telemetry';

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export function loadStoredTokens(): AuthTokens | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as AuthTokens) : null;
  } catch {
    return null;
  }
}

export function saveStoredTokens(tokens: AuthTokens | null): void {
  if (!tokens) {
    sessionStorage.removeItem(STORAGE_KEY);
  } else {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(tokens));
  }
}

async function parseErrorMessage(resp: Response): Promise<string> {
  try {
    const data = await resp.json();
    if (typeof data?.detail === 'string') return data.detail;
    if (typeof data?.title === 'string') return data.title;
    if (typeof data?.message === 'string') return data.message;
    if (data?.errors && typeof data.errors === 'object') {
      const first = Object.values(data.errors).flat()[0];
      if (typeof first === 'string') return first;
    }
  } catch {
    // ignore JSON parse errors
  }
  return `Request failed (${resp.status})`;
}

async function rawFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const resp = await tracedFetch(path, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...(init?.headers ?? {}),
    },
  });
  if (!resp.ok) {
    throw new ApiError(resp.status, await parseErrorMessage(resp));
  }
  if (resp.status === 204) {
    return undefined as T;
  }
  return (await resp.json()) as T;
}

let refreshPromise: Promise<AuthTokens | null> | null = null;

async function refreshTokensIfPossible(onTokensUpdated?: (t: AuthTokens | null) => void): Promise<AuthTokens | null> {
  const current = loadStoredTokens();
  if (!current?.refreshToken) return null;

  if (!refreshPromise) {
    refreshPromise = rawFetch<AuthTokens>('/api/auth/refresh', {
      method: 'POST',
      body: JSON.stringify({ refreshToken: current.refreshToken }),
    })
      .then((next) => {
        saveStoredTokens(next);
        onTokensUpdated?.(next);
        return next;
      })
      .catch(() => {
        saveStoredTokens(null);
        onTokensUpdated?.(null);
        return null;
      })
      .finally(() => {
        refreshPromise = null;
      });
  }
  return refreshPromise;
}

export async function authFetch<T>(
  path: string,
  init: RequestInit = {},
  onTokensUpdated?: (t: AuthTokens | null) => void
): Promise<T> {
  const tokens = loadStoredTokens();
  if (!tokens?.accessToken) {
    throw new ApiError(401, 'Not authenticated');
  }

  try {
    return await rawFetch<T>(path, {
      ...init,
      headers: {
        ...(init.headers ?? {}),
        Authorization: `Bearer ${tokens.accessToken}`,
      },
    });
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      const refreshed = await refreshTokensIfPossible(onTokensUpdated);
      if (refreshed?.accessToken) {
        return await rawFetch<T>(path, {
          ...init,
          headers: {
            ...(init.headers ?? {}),
            Authorization: `Bearer ${refreshed.accessToken}`,
          },
        });
      }
    }
    throw err;
  }
}

export const authApi = {
  register(username: string, password: string): Promise<RegisterResponse> {
    return rawFetch('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    });
  },
  async login(username: string, password: string): Promise<AuthTokens> {
    const tokens = await rawFetch<AuthTokens>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    });
    saveStoredTokens(tokens);
    return tokens;
  },
  recover(username: string, recoveryCode: string, newPassword: string): Promise<RecoverResponse> {
    return rawFetch('/api/auth/recover', {
      method: 'POST',
      body: JSON.stringify({ username, recoveryCode, newPassword }),
    });
  },
};

export const playerApi = {
  getMe(onTokensUpdated?: (t: AuthTokens | null) => void): Promise<PlayerProfile> {
    return authFetch('/api/players/me', {}, onTokensUpdated);
  },
  getMyMatches(limit = 15, onTokensUpdated?: (t: AuthTokens | null) => void): Promise<PlayerMatch[]> {
    return authFetch(`/api/players/me/matches?limit=${limit}`, {}, onTokensUpdated);
  },
  getLeaderboard(limit = 15): Promise<PlayerProfile[]> {
    return rawFetch(`/api/players/leaderboard?limit=${limit}`);
  },
};

export const matchmakingApi = {
  joinQueue(onTokensUpdated?: (t: AuthTokens | null) => void): Promise<MatchmakingTicket> {
    return authFetch('/api/matchmaking/queue', { method: 'POST' }, onTokensUpdated);
  },
  leaveQueue(onTokensUpdated?: (t: AuthTokens | null) => void): Promise<{ cancelled: boolean }> {
    return authFetch('/api/matchmaking/queue', { method: 'DELETE' }, onTokensUpdated);
  },
  getTicket(ticketId: string, onTokensUpdated?: (t: AuthTokens | null) => void): Promise<MatchmakingTicket> {
    return authFetch(`/api/matchmaking/tickets/${ticketId}`, {}, onTokensUpdated);
  },
  getStatus(): Promise<MatchmakingStatus> {
    return rawFetch('/api/matchmaking/status');
  },
};

export function buildGameWebSocketUrl(wsPath: string, accessToken: string): string {
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const normalized = wsPath.startsWith('/') ? wsPath : `/${wsPath}`;
  const sep = normalized.includes('?') ? '&' : '?';
  return `${proto}//${window.location.host}${normalized}${sep}token=${encodeURIComponent(accessToken)}`;
}
