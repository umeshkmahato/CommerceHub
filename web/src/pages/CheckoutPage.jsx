import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../api/client.js';

/** Checkout — collects shipping address, POSTs /orders, saga runs async. */
export default function CheckoutPage() {
  const [address, setAddress] = useState('{"line1":"","city":"","state":"","postalCode":"","country":"US"}');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const nav = useNavigate();

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError('');
    try {
      const { data } = await api.post('/v1/orders', { shippingAddressJson: address });
      nav(`/orders?highlight=${data.orderNumber}`);
    } catch (err) {
      setError(err.response?.data?.message || 'Checkout failed');
      setBusy(false);
    }
  };

  return (
    <div className="container auth-card">
      <h2>Checkout</h2>
      {error && <div className="error">{error}</div>}
      <p className="muted">Enter shipping address as JSON (or wire a form later):</p>
      <textarea rows="5" value={address} onChange={e => setAddress(e.target.value)} />
      <button onClick={submit} disabled={busy}>{busy ? 'Placing order…' : 'Place order'}</button>
    </div>
  );
}
