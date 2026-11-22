import { useCallback, useEffect, useState } from 'react';
import { ApiError, matchmakingApi, playerApi } from '../api';
import type {
  AuthTokens,
  MatchmakingStatus,
  MatchmakingTicket,
  PlayerMatch,
  PlayerProfile,
} from '../types';

interface Props {
  onTokensUpdated: (tokens: AuthTokens | null) => void;
  onMatchReady: (matchId: string, wsPath: string) => void;
}

function formatReason(reason: string): string {
  switch (reason) {
    case 'SCORE_LIMIT':
      return 'FULL GAME';
    case 'FORFEIT':
      return 'FORFEIT';
    case 'DISCONNECT':
      return 'DISCONNECT';
    default:
      return reason.replace(/_/g, ' ');
  }
}

export function LobbyView({ onTokensUpdated, onMatchReady }: Props) {
  const [profile, setProfile] = useState<PlayerProfile | null>(null);
  const [matches, setMatches] = useState<PlayerMatch[]>([]);
  const [leaderboard, setLeaderboard] = useState<PlayerProfile[]>([]);
  const [arenaStatus, setArenaStatus] = useState<MatchmakingStatus | null>(null);
  const [ticket, setTicket] = useState<MatchmakingTicket | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadLobbyData = useCallback(async () => {
    try {
      const [me, history, board, status] = await Promise.all([
        playerApi.getMe(onTokensUpdated),
        playerApi.getMyMatches(10, onTokensUpdated),
        playerApi.getLeaderboard(10),
        matchmakingApi.getStatus(),
      ]);
      setProfile(me);
      setMatches(history);
      setLeaderboard(board);
      setArenaStatus(status);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        onTokensUpdated(null);
        return;
      }
      setError(err instanceof Error ? err.message : 'Unable to load player records.');
    }
  }, [onTokensUpdated]);

  useEffect(() => {
    void loadLobbyData();
    const timer = window.setInterval(() => {
      void matchmakingApi.getStatus().then(setArenaStatus).catch(() => {});
    }, 3000);
    return () => window.clearInterval(timer);
  }, [loadLobbyData]);

  useEffect(() => {
    if (!ticket) return;
    if (ticket.status === 'READY' && ticket.matchId && ticket.wsPath) {
      onMatchReady(ticket.matchId, ticket.wsPath);
      return;
    }
    if (ticket.status !== 'QUEUED' && ticket.status !== 'PROVISIONING') {
      return;
    }

    const poller = window.setInterval(async () => {
      try {
        const updated = await matchmakingApi.getTicket(ticket.ticketId, onTokensUpdated);
        setTicket(updated);
        if (updated.status === 'READY' && updated.matchId && updated.wsPath) {
          onMatchReady(updated.matchId, updated.wsPath);
        }
      } catch (err) {
        if (err instanceof ApiError && err.status === 401) {
          onTokensUpdated(null);
        }
      }
    }, 500);

    return () => window.clearInterval(poller);
  }, [ticket, onMatchReady, onTokensUpdated]);

  async function handleJoinQueue() {
    setError(null);
    setBusy(true);
    try {
      const created = await matchmakingApi.joinQueue(onTokensUpdated);
      setTicket(created);
      if (created.status === 'READY' && created.matchId && created.wsPath) {
        onMatchReady(created.matchId, created.wsPath);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to join match queue.');
    } finally {
      setBusy(false);
    }
  }

  async function handleLeaveQueue() {
    setError(null);
    setBusy(true);
    try {
      await matchmakingApi.leaveQueue(onTokensUpdated);
      setTicket(null);
      const status = await matchmakingApi.getStatus();
      setArenaStatus(status);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to leave queue.');
    } finally {
      setBusy(false);
    }
  }

  const isSearching = ticket?.status === 'QUEUED' || ticket?.status === 'PROVISIONING';

  return (
    <div>
      {error && <div className="alert alert-error">{error}</div>}

      <div className="lobby-grid">
        {/* Player Dossier */}
        <div className="panel">
          <div className="panel-header">
            <h2 className="panel-title">{profile ? profile.username : 'LOADING...'}</h2>
            <span className="tag green">RATING {profile?.elo ?? 1000}</span>
          </div>

          <div className="stats-grid">
            <div className="stat-tile">
              <div className="val">{profile?.matchesPlayed ?? 0}</div>
              <div className="lbl">Played</div>
            </div>
            <div className="stat-tile">
              <div className="val" style={{ color: 'var(--phosphor)' }}>{profile?.wins ?? 0}</div>
              <div className="lbl">Wins</div>
            </div>
            <div className="stat-tile">
              <div className="val" style={{ color: 'var(--crimson)' }}>{profile?.losses ?? 0}</div>
              <div className="lbl">Losses</div>
            </div>
            <div className="stat-tile">
              <div className="val">{profile ? `${Math.round(profile.winRate)}%` : '0%'}</div>
              <div className="lbl">Win %</div>
            </div>
          </div>

          <div className="profile-footer">
            <span>
              POINTS FOR: <strong style={{ color: 'var(--ink)' }}>{profile?.totalPointsScored ?? 0}</strong>
            </span>
            <span>
              POINTS AGAINST: <strong style={{ color: 'var(--ink)' }}>{profile?.totalPointsConceded ?? 0}</strong>
            </span>
          </div>
        </div>

        {/* Versus Matchmaking */}
        <div className="panel queue-panel">
          <div>
            <div className="panel-header">
              <h2 className="panel-title">Versus Match</h2>
              <span className={`led ${isSearching ? 'amber' : 'green'}`}>
                {isSearching ? 'Matching' : 'Ready'}
              </span>
            </div>

            <div className="arcade-counters">
              <div className="counter-cell">
                <div className="num">{arenaStatus?.queuedPlayers ?? 0}</div>
                <div className="cap">In Queue</div>
              </div>
              <div className="counter-cell">
                <div className="num">{arenaStatus?.activeMatches ?? 0}</div>
                <div className="cap">Live Courts</div>
              </div>
              <div className="counter-cell">
                <div className="num">{arenaStatus?.availableSlots ?? 0}</div>
                <div className="cap">Open Courts</div>
              </div>
            </div>
          </div>

          {!isSearching ? (
            <button className="btn btn-primary btn-lg" disabled={busy} onClick={handleJoinQueue}>
              {busy ? 'CONNECTING...' : 'FIND MATCH'}
            </button>
          ) : (
            <button
              className="btn btn-danger btn-lg"
              disabled={busy || ticket?.status === 'PROVISIONING'}
              onClick={handleLeaveQueue}
            >
              {ticket?.status === 'PROVISIONING' ? 'PREPARING COURT...' : 'CANCEL SEARCH'}
            </button>
          )}

          {ticket && (
            <div className="queue-status-box">
              <span>
                {ticket.status === 'QUEUED' && (
                  <span style={{ color: 'var(--amber)' }}>
                    <span className="blink">●</span> SEARCHING FOR OPPONENT...
                  </span>
                )}
                {ticket.status === 'PROVISIONING' && (
                  <span style={{ color: 'var(--phosphor)' }}>
                    <span className="blink">●</span> OPPONENT FOUND — STARTING COURT...
                  </span>
                )}
                {ticket.status === 'COMPENSATED' && (
                  <span style={{ color: 'var(--crimson)' }}>COURT UNAVAILABLE — PLEASE TRY AGAIN</span>
                )}
                {ticket.status === 'CANCELLED' && <span className="muted">SEARCH CANCELLED</span>}
                {ticket.status === 'FINISHED' && <span className="muted">LAST MATCH COMPLETED</span>}
              </span>
              <span className="muted" style={{ fontSize: '0.72rem' }}>
                #{ticket.ticketId.slice(0, 8).toUpperCase()}
              </span>
            </div>
          )}
        </div>
      </div>

      {/* Bottom Row: Recent Matches & High Scores */}
      <div className="lobby-grid">
        <div className="panel">
          <div className="panel-header">
            <h2 className="panel-title">Match Log</h2>
            <button
              className="btn btn-ghost"
              style={{ padding: '4px 10px', fontSize: '0.72rem' }}
              onClick={() => void loadLobbyData()}
            >
              Refresh
            </button>
          </div>
          {matches.length === 0 ? (
            <p className="muted" style={{ margin: 0, fontSize: '0.84rem' }}>
              No matches recorded yet. Start a match to claim your rank.
            </p>
          ) : (
            <table className="table">
              <thead>
                <tr>
                  <th>Opponent</th>
                  <th>Score</th>
                  <th>Outcome</th>
                  <th>Finish</th>
                </tr>
              </thead>
              <tbody>
                {matches.map((m) => (
                  <tr key={`${m.matchId}-${m.finishedAt}`}>
                    <td>{m.opponentUsername}</td>
                    <td>
                      {m.playerScore} - {m.opponentScore}
                    </td>
                    <td>
                      <span className={`tag ${m.result === 'WIN' ? 'green' : m.result === 'LOSS' ? 'red' : 'amber'}`}>
                        {m.result}
                      </span>
                    </td>
                    <td className="muted" style={{ fontSize: '0.76rem' }}>
                      {formatReason(m.reason)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        <div className="panel">
          <div className="panel-header">
            <h2 className="panel-title">High Scores</h2>
            <span className="tag amber">Top 10</span>
          </div>
          {leaderboard.length === 0 ? (
            <p className="muted" style={{ margin: 0, fontSize: '0.84rem' }}>
              Leaderboard is empty.
            </p>
          ) : (
            <table className="table">
              <thead>
                <tr>
                  <th>Rank</th>
                  <th>Player</th>
                  <th>Rating</th>
                  <th>W-L</th>
                </tr>
              </thead>
              <tbody>
                {leaderboard.map((p, idx) => (
                  <tr key={p.playerId}>
                    <td style={{ color: idx === 0 ? 'var(--amber)' : 'var(--muted)' }}>
                      {String(idx + 1).padStart(2, '0')}
                    </td>
                    <td>
                      {p.username}
                      {p.playerId === profile?.playerId && (
                        <span className="tag green" style={{ marginLeft: 8, padding: '1px 6px' }}>
                          YOU
                        </span>
                      )}
                    </td>
                    <td style={{ color: idx === 0 ? 'var(--amber)' : 'var(--ink)' }}>{p.elo}</td>
                    <td className="muted">
                      {p.wins}-{p.losses}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
}
