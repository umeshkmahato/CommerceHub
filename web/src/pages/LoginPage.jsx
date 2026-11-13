import React, { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext.jsx';

export default function LoginPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const { login } = useAuth();
  const nav = useNavigate();

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError('');
    try {
      await login(email, password);
      nav('/');
    } catch (err) {
      setError(err.response?.data?.message || 'Login failed');
    } finally { setBusy(false); }
  };

  return (
    <div className="auth-card">
      <h2>Sign in</h2>
      {error && <div className="error">{error}</div>}
      <form onSubmit={submit}>
        <input type="email" placeholder="Email" value={email}
               onChange={e => setEmail(e.target.value)} required />
        <input type="password" placeholder="Password" value={password}
               onChange={e => setPassword(e.target.value)} required />
        <button disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
      </form>
      <p>No account? <Link to="/register">Register</Link></p>
    </div>
  );
}
