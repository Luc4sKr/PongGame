import { FormEvent, useState } from 'react';
import { ApiError, authApi } from '../api';
import type { AuthTokens } from '../types';

interface Props {
  onAuthenticated: (tokens: AuthTokens) => void;
}

type Mode = 'login' | 'register' | 'recover';

export function AuthView({ onAuthenticated }: Props) {
  const [mode, setMode] = useState<Mode>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [recoveryCode, setRecoveryCode] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [issuedRecoveryCode, setIssuedRecoveryCode] = useState<string | null>(null);
  const [infoMessage, setInfoMessage] = useState<string | null>(null);

  const resetMessages = () => {
    setError(null);
    setInfoMessage(null);
    setIssuedRecoveryCode(null);
  };

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    resetMessages();
    setLoading(true);
    try {
      if (mode === 'login') {
        const tokens = await authApi.login(username.trim(), password);
        onAuthenticated(tokens);
      } else if (mode === 'register') {
        const res = await authApi.register(username.trim(), password);
        setIssuedRecoveryCode(res.recoveryCode);
        setInfoMessage(`Player "${res.username}" registered. Save your recovery key below, then sign in.`);
        setMode('login');
      } else {
        const res = await authApi.recover(username.trim(), recoveryCode.trim(), newPassword);
        setIssuedRecoveryCode(res.recoveryCode);
        setInfoMessage('Password updated. Save your new recovery key and sign in.');
        setPassword(newPassword);
        setMode('login');
      }
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.status === 409) setError('Callsign is already taken.');
        else if (err.status === 401) setError('Invalid credentials or recovery key.');
        else if (err.status === 429) setError('Too many attempts. Please wait a moment and try again.');
        else setError(err.message);
      } else {
        setError('Unable to reach server. Please try again.');
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="auth-wrapper">
      <div className="panel">
        <div className="panel-header">
          <h2 className="panel-title">Player Access</h2>
          <span className="tag">Arcade Pass</span>
        </div>

        <div className="tabs">
          {(['login', 'register', 'recover'] as Mode[]).map((tab) => (
            <button
              key={tab}
              type="button"
              className={`tab-btn ${mode === tab ? 'active' : ''}`}
              onClick={() => {
                setMode(tab);
                setError(null);
              }}
            >
              {tab === 'login' ? 'Sign In' : tab === 'register' ? 'New Player' : 'Recover'}
            </button>
          ))}
        </div>

        {error && <div className="alert alert-error">{error}</div>}
        {infoMessage && <div className="alert alert-success">{infoMessage}</div>}

        {issuedRecoveryCode && (
          <div className="recovery-box">
            <div className="label">Recovery Key — Store Safely</div>
            <div>{issuedRecoveryCode}</div>
          </div>
        )}

        <form onSubmit={handleSubmit}>
          <div className="field">
            <label htmlFor="username">Callsign (3–32 characters)</label>
            <input
              id="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="PLAYER_ONE"
              minLength={3}
              maxLength={32}
              required
              autoFocus
            />
          </div>

          {mode !== 'recover' && (
            <div className="field">
              <label htmlFor="password">Password (12–128 characters)</label>
              <input
                id="password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="••••••••••••"
                minLength={12}
                maxLength={128}
                required
              />
            </div>
          )}

          {mode === 'recover' && (
            <>
              <div className="field">
                <label htmlFor="recoveryCode">Recovery Key</label>
                <input
                  id="recoveryCode"
                  value={recoveryCode}
                  onChange={(e) => setRecoveryCode(e.target.value)}
                  placeholder="Enter saved recovery key"
                  required
                />
              </div>
              <div className="field">
                <label htmlFor="newPassword">New Password (12–128 characters)</label>
                <input
                  id="newPassword"
                  type="password"
                  value={newPassword}
                  onChange={(e) => setNewPassword(e.target.value)}
                  placeholder="••••••••••••"
                  minLength={12}
                  maxLength={128}
                  required
                />
              </div>
            </>
          )}

          <button className="btn btn-primary btn-lg" style={{ width: '100%', marginTop: 6 }} disabled={loading}>
            {loading
              ? 'PLEASE WAIT...'
              : mode === 'login'
              ? 'ENTER ARCADE'
              : mode === 'register'
              ? 'CREATE PROFILE'
              : 'RESET PASSWORD'}
          </button>
        </form>
      </div>
    </div>
  );
}
