export interface AuthTokens {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  refreshToken: string;
}

export interface RegisterResponse {
  userId: string;
  username: string;
  recoveryCode: string;
}

export interface RecoverResponse {
  recoveryCode: string;
}

export interface PlayerProfile {
  playerId: string;
  username: string;
  elo: number;
  matchesPlayed: number;
  wins: number;
  losses: number;
  draws: number;
  winRate: number;
  totalPointsScored: number;
  totalPointsConceded: number;
  updatedAt: string;
}

export interface PlayerMatch {
  matchId: string;
  opponentId: string;
  opponentUsername: string;
  playerScore: number;
  opponentScore: number;
  result: 'WIN' | 'LOSS' | 'DRAW' | string;
  reason: string;
  durationSeconds: number;
  finishedAt: string;
}

export interface MatchmakingTicket {
  ticketId: string;
  playerId: string;
  username: string;
  status: 'QUEUED' | 'PROVISIONING' | 'READY' | 'COMPENSATED' | 'CANCELLED' | 'FINISHED' | string;
  matchId: string | null;
  wsPath: string | null;
  correlationId: string;
  reason: string | null;
  updatedAt: string;
}

export interface MatchmakingStatus {
  queuedPlayers: number;
  activeMatches: number;
  maxConcurrentMatches: number;
  availableSlots: number;
  pendingOutboxMessages: number;
}

export interface PaddleState {
  playerId: string;
  username: string;
  y: number;
  height: number;
  score: number;
  connected: boolean;
}

export interface BallState {
  x: number;
  y: number;
  vx: number;
  vy: number;
  radius: number;
}

export interface GameStateFrame {
  type: 'state';
  matchId: string;
  instanceId: string;
  status: 'WAITING_FOR_PLAYERS' | 'PLAYING' | 'FINISHED' | 'CANCELLED' | string;
  tick: number;
  winningScore: number;
  board: { width: number; height: number };
  ball: BallState;
  paddles: {
    left: PaddleState;
    right: PaddleState;
  };
  winnerId?: string | null;
  finishReason?: string;
}

export interface GameWelcomeFrame {
  type: 'welcome';
  matchId: string;
  side: 'left' | 'right';
  playerId: string;
  username: string;
  instanceId: string;
  state: GameStateFrame;
}
