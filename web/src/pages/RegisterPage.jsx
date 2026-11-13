import React, { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext.jsx';

export default function RegisterPage() {
  const [form, setForm] = useState({ email: '', password: '', firstName: '', lastName: '' });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const { register } = useAuth();
  const nav = useNavigate();

  const set = (k) => (e) => setForm(f => ({ ...f, [k]: e.target.value }));

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError('');
    try {
      await register(form);
      nav('/');
    } catch (err) {
      setError(err.response?.data?.message || 'Registration failed');
    } finally { setBusy(false); }
  };

  return (
    <div className="auth-card">
      <h2>Create account</h2>
      {error && <div className="error">{error}</div>}
      <form onSubmit={submit}>
        <input placeholder="First name" value={form.firstName} onChange={set('firstName')} required />
        <input placeholder="Last name" value={form.lastName} onChange={set('lastName')} required />
        <input type="email" placeholder="Email" value={form.email} onChange={set('email')} required />
        <input type="password" placeholder="Password (8+ chars, letters+digits)"
               value={form.password} onChange={set('password')} required />
        <button disabled={busy}>{busy ? 'Creating…' : 'Register'}</button>
      </form>
      <p>Have an account? <Link to="/login">Sign in</Link></p>
    </div>
  );
}
