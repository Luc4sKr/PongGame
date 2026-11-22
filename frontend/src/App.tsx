import { useState } from 'react';
import { loadStoredTokens, saveStoredTokens } from './api';
import { AuthView } from './components/AuthView';
import { GameArenaView } from './components/GameArenaView';
import { LobbyView } from './components/LobbyView';
import type { AuthTokens } from './types';

interface ActiveMatch {
  matchId: string;
  wsPath: string;
}

export function App() {
  const [tokens, setTokens] = useState<AuthTokens | null>(() => loadStoredTokens());
  const [activeMatch, setActiveMatch] = useState<ActiveMatch | null>(null);

  function handleLogout() {
    saveStoredTokens(null);
    setActiveMatch(null);
    setTokens(null);
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand">
          <div className="brand-mark" aria-hidden="true">
            <span className="paddle left" />
            <span className="ball" />
            <span className="paddle right" />
          </div>
          <div>
            <h1>PONG</h1>
            <small>Online Arcade Championship</small>
          </div>
        </div>

        <div className="topbar-right">
          <span className="led green">Online</span>
          {tokens && (
            <button className="btn btn-ghost" onClick={handleLogout}>
              Sign Out
            </button>
          )}
        </div>
      </header>

      {!tokens ? (
        <AuthView onAuthenticated={setTokens} />
      ) : activeMatch ? (
        <GameArenaView
          matchId={activeMatch.matchId}
          wsPath={activeMatch.wsPath}
          onExit={() => setActiveMatch(null)}
        />
      ) : (
        <LobbyView
          onTokensUpdated={setTokens}
          onMatchReady={(matchId, wsPath) => setActiveMatch({ matchId, wsPath })}
        />
      )}
    </div>
  );
}
