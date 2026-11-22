import { useEffect, useRef, useState } from 'react';
import { buildGameWebSocketUrl, loadStoredTokens } from '../api';
import type { GameStateFrame, GameWelcomeFrame } from '../types';

interface Props {
  matchId: string;
  wsPath: string;
  onExit: () => void;
}

type Direction = 'UP' | 'DOWN' | 'STOP';

const PADDLE_WIDTH = 14;
const PADDLE_MARGIN = 24;
const TRAIL_LENGTH = 5;

export function GameArenaView({ matchId, wsPath, onExit }: Props) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const latestStateRef = useRef<GameStateFrame | null>(null);
  const trailRef = useRef<Array<{ x: number; y: number }>>([]);
  const activeDirRef = useRef<Direction>('STOP');
  const keysRef = useRef<{ up: boolean; down: boolean }>({ up: false, down: false });

  const [connStatus, setConnStatus] = useState<'CONNECTING' | 'LIVE' | 'CLOSED' | 'ERROR'>('CONNECTING');
  const [mySide, setMySide] = useState<'left' | 'right' | null>(null);
  const [myPlayerId, setMyPlayerId] = useState<string | null>(null);
  const [gameState, setGameState] = useState<GameStateFrame | null>(null);

  function sendDirection(dir: Direction) {
    if (activeDirRef.current === dir) return;
    activeDirRef.current = dir;
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'input', direction: dir }));
    }
  }

  function sendForfeit() {
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'forfeit' }));
    }
  }

  useEffect(() => {
    const tokens = loadStoredTokens();
    if (!tokens?.accessToken) {
      setConnStatus('ERROR');
      return;
    }

    const url = buildGameWebSocketUrl(wsPath, tokens.accessToken);
    const ws = new WebSocket(url);
    wsRef.current = ws;

    ws.onopen = () => setConnStatus('LIVE');

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data) as GameWelcomeFrame | GameStateFrame;
        if (msg.type === 'welcome') {
          setMySide(msg.side);
          setMyPlayerId(msg.playerId);
          if (msg.state) {
            latestStateRef.current = msg.state;
            setGameState(msg.state);
          }
        } else if (msg.type === 'state') {
          latestStateRef.current = msg;
          setGameState(msg);
          if (msg.status === 'PLAYING') {
            trailRef.current.push({ x: msg.ball.x, y: msg.ball.y });
            if (trailRef.current.length > TRAIL_LENGTH) {
              trailRef.current.shift();
            }
          } else {
            trailRef.current = [];
          }
        }
      } catch {
        // ignore malformed frames
      }
    };

    ws.onerror = () => setConnStatus('ERROR');
    ws.onclose = () => setConnStatus('CLOSED');

    return () => {
      ws.close();
      wsRef.current = null;
    };
  }, [wsPath]);

  useEffect(() => {
    const syncDir = () => {
      if (keysRef.current.up && !keysRef.current.down) sendDirection('UP');
      else if (keysRef.current.down && !keysRef.current.up) sendDirection('DOWN');
      else sendDirection('STOP');
    };

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'ArrowUp' || e.key === 'w' || e.key === 'W') {
        e.preventDefault();
        keysRef.current.up = true;
        syncDir();
      } else if (e.key === 'ArrowDown' || e.key === 's' || e.key === 'S') {
        e.preventDefault();
        keysRef.current.down = true;
        syncDir();
      }
    };

    const onKeyUp = (e: KeyboardEvent) => {
      if (e.key === 'ArrowUp' || e.key === 'w' || e.key === 'W') {
        keysRef.current.up = false;
        syncDir();
      } else if (e.key === 'ArrowDown' || e.key === 's' || e.key === 'S') {
        keysRef.current.down = false;
        syncDir();
      }
    };

    window.addEventListener('keydown', onKeyDown);
    window.addEventListener('keyup', onKeyUp);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      window.removeEventListener('keyup', onKeyUp);
    };
  }, []);

  useEffect(() => {
    let rafId = 0;

    const render = () => {
      const canvas = canvasRef.current;
      const st = latestStateRef.current;
      if (canvas) {
        const ctx = canvas.getContext('2d');
        if (ctx) {
          const w = st?.board.width ?? 800;
          const h = st?.board.height ?? 600;
          if (canvas.width !== w || canvas.height !== h) {
            canvas.width = w;
            canvas.height = h;
          }

          // Pitch-black CRT background
          ctx.fillStyle = '#040507';
          ctx.fillRect(0, 0, w, h);

          // Classic top & bottom court boundary rails
          ctx.fillStyle = '#e8edf2';
          ctx.fillRect(0, 0, w, 6);
          ctx.fillRect(0, h - 6, w, 6);

          // Classic chunky dashed center net
          ctx.fillStyle = 'rgba(232, 237, 242, 0.35)';
          for (let y = 18; y < h - 18; y += 26) {
            ctx.fillRect(Math.floor(w / 2) - 3, y, 6, 14);
          }

          if (st) {
            // Classic retro arcade scoreboard
            ctx.textAlign = 'center';
            ctx.fillStyle = '#f4f7f5';
            ctx.font = '44px "Press Start 2P", monospace';
            ctx.fillText(String(st.paddles.left.score), w * 0.27, 74);
            ctx.fillText(String(st.paddles.right.score), w * 0.73, 74);

            // Player callsigns
            ctx.font = '600 13px "IBM Plex Mono", monospace';
            ctx.fillStyle = mySide === 'left' ? '#39ff88' : '#7f8b9f';
            ctx.fillText(
              `${st.paddles.left.username.toUpperCase()}${mySide === 'left' ? ' [P1]' : ''}`,
              w * 0.27,
              102
            );
            ctx.fillStyle = mySide === 'right' ? '#39ff88' : '#7f8b9f';
            ctx.fillText(
              `${st.paddles.right.username.toUpperCase()}${mySide === 'right' ? ' [P2]' : ''}`,
              w * 0.73,
              102
            );

            // Left paddle (sharp classic phosphor bar)
            ctx.save();
            ctx.fillStyle = mySide === 'left' ? '#f4f7f5' : '#cfd8e3';
            ctx.shadowColor = mySide === 'left' ? 'rgba(57, 255, 136, 0.45)' : 'rgba(244, 247, 245, 0.25)';
            ctx.shadowBlur = 10;
            ctx.fillRect(PADDLE_MARGIN, Math.round(st.paddles.left.y), PADDLE_WIDTH, st.paddles.left.height);
            ctx.restore();

            // Right paddle
            ctx.save();
            ctx.fillStyle = mySide === 'right' ? '#f4f7f5' : '#cfd8e3';
            ctx.shadowColor = mySide === 'right' ? 'rgba(57, 255, 136, 0.45)' : 'rgba(244, 247, 245, 0.25)';
            ctx.shadowBlur = 10;
            ctx.fillRect(w - PADDLE_MARGIN - PADDLE_WIDTH, Math.round(st.paddles.right.y), PADDLE_WIDTH, st.paddles.right.height);
            ctx.restore();

            // Subtle phosphor trail behind classic square Pong ball
            const size = st.ball.radius * 2;
            const trail = trailRef.current;
            for (let i = 0; i < trail.length; i++) {
              const alpha = ((i + 1) / (trail.length + 2)) * 0.28;
              ctx.fillStyle = `rgba(244, 247, 245, ${alpha})`;
              ctx.fillRect(
                Math.round(trail[i].x - st.ball.radius),
                Math.round(trail[i].y - st.ball.radius),
                size,
                size
              );
            }

            // Classic square Pong ball
            ctx.save();
            ctx.fillStyle = '#ffffff';
            ctx.shadowColor = 'rgba(255, 255, 255, 0.7)';
            ctx.shadowBlur = 12;
            ctx.fillRect(
              Math.round(st.ball.x - st.ball.radius),
              Math.round(st.ball.y - st.ball.radius),
              size,
              size
            );
            ctx.restore();
          }
        }
      }
      rafId = window.requestAnimationFrame(render);
    };

    rafId = window.requestAnimationFrame(render);
    return () => window.cancelAnimationFrame(rafId);
  }, [mySide]);

  const isFinished = gameState?.status === 'FINISHED' || gameState?.status === 'CANCELLED';
  const didWin = isFinished && gameState?.winnerId && gameState.winnerId === myPlayerId;

  return (
    <div className="arena-shell">
      <div className="arena-bar">
        <div className="arena-leds">
          <span className={`led ${connStatus === 'LIVE' ? 'green' : connStatus === 'CONNECTING' ? 'amber' : 'red'}`}>
            {connStatus}
          </span>
          <span className="tag green">
            {mySide === 'left' ? 'PLAYER 1 · LEFT' : mySide === 'right' ? 'PLAYER 2 · RIGHT' : 'ASSIGNING...'}
          </span>
          <span className="tag">FIRST TO {gameState?.winningScore ?? 5}</span>
        </div>
        <span className="muted" style={{ fontSize: '0.74rem', letterSpacing: '0.08em' }}>
          COURT #{matchId.slice(0, 8).toUpperCase()}
        </span>
      </div>

      <div className="crt-bezel">
        <div className="canvas-frame">
          <canvas ref={canvasRef} width={800} height={600} />

          {gameState?.status === 'WAITING_FOR_PLAYERS' && (
            <div className="arena-overlay">
              <span className="led amber">Standby</span>
              <h2 className="overlay-heading">WAITING FOR OPPONENT</h2>
              <p className="muted" style={{ margin: 0, fontSize: '0.84rem' }}>
                {gameState.paddles.left.username.toUpperCase()}:{' '}
                {gameState.paddles.left.connected ? 'READY' : 'JOINING...'} &nbsp;|&nbsp;{' '}
                {gameState.paddles.right.username.toUpperCase()}:{' '}
                {gameState.paddles.right.connected ? 'READY' : 'JOINING...'}
              </p>
            </div>
          )}

          {isFinished && (
            <div className="arena-overlay">
              <span className={`tag ${didWin ? 'green' : 'red'}`}>
                {gameState?.status === 'CANCELLED' ? 'MATCH CANCELLED' : didWin ? 'WINNER' : 'GAME OVER'}
              </span>
              <h2 className="overlay-heading" style={{ color: didWin ? 'var(--phosphor)' : 'var(--ink)' }}>
                {gameState?.status === 'CANCELLED' ? 'COURT CLOSED' : didWin ? 'VICTORY' : 'DEFEAT'}
              </h2>
              <div className="overlay-score">
                {gameState?.paddles.left.username.toUpperCase()} {gameState?.paddles.left.score} —{' '}
                {gameState?.paddles.right.score} {gameState?.paddles.right.username.toUpperCase()}
              </div>
              <button className="btn btn-primary btn-lg" onClick={onExit}>
                RETURN TO LOBBY
              </button>
            </div>
          )}
        </div>
      </div>

      <div className="arena-controls">
        <div className="touch-controls">
          <button
            className="btn"
            onMouseDown={() => sendDirection('UP')}
            onMouseUp={() => sendDirection('STOP')}
            onMouseLeave={() => sendDirection('STOP')}
            onTouchStart={() => sendDirection('UP')}
            onTouchEnd={() => sendDirection('STOP')}
          >
            ▲ UP (W / ↑)
          </button>
          <button
            className="btn"
            onMouseDown={() => sendDirection('DOWN')}
            onMouseUp={() => sendDirection('STOP')}
            onMouseLeave={() => sendDirection('STOP')}
            onTouchStart={() => sendDirection('DOWN')}
            onTouchEnd={() => sendDirection('STOP')}
          >
            ▼ DOWN (S / ↓)
          </button>
        </div>

        <div style={{ display: 'flex', gap: 10 }}>
          {!isFinished && (
            <button className="btn btn-danger" onClick={sendForfeit}>
              Forfeit
            </button>
          )}
          <button className="btn btn-ghost" onClick={onExit}>
            Exit Court
          </button>
        </div>
      </div>
    </div>
  );
}
