import React, { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import api from '../api/client.js';

const STATUS_COLOR = {
  PENDING: '#b45309', CONFIRMED: '#15803d', SHIPPED: '#1d4ed8',
  DELIVERED: '#166534', CANCELLED: '#b91c1c', FAILED: '#b91c1c'
};

export default function OrdersPage() {
  const [orders, setOrders] = useState([]);
  const [params] = useSearchParams();
  const highlight = params.get('highlight');

  useEffect(() => {
    api.get('/v1/orders', { params: { page: 0, size: 20 } })
      .then(r => setOrders(r.data.content)).catch(() => setOrders([]));
  }, []);

  const cancel = async (id) => {
    await api.post(`/v1/orders/${id}/cancel`, { reason: 'Customer request' });
    setOrders(os => os.map(o => o.id === id ? { ...o, status: 'CANCELLED' } : o));
  };

  return (
    <div className="container">
      <h2>My orders</h2>
      {highlight && <div className="ok">Order {highlight} placed — tracking the saga…</div>}
      {orders.length === 0 && <p>No orders yet.</p>}
      {orders.map(o => (
        <div key={o.id} className="order-card">
          <div className="order-head">
            <strong>{o.orderNumber}</strong>
            <span style={{ color: STATUS_COLOR[o.status] }}>{o.status}</span>
            <span>${o.totalAmount}</span>
          </div>
          <small>{new Date(o.createdAt).toLocaleString()} · {o.items?.length || 0} item(s)</small>
          {(o.status === 'PENDING' || o.status === 'CONFIRMED') &&
            <button className="danger" onClick={() => cancel(o.id)}>Cancel</button>}
        </div>
      ))}
    </div>
  );
}
