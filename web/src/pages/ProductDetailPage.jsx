import React, { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import api from '../api/client.js';
import { useAuth } from '../context/AuthContext.jsx';

export default function ProductDetailPage() {
  const { id } = useParams();
  const [p, setP] = useState(null);
  const [qty, setQty] = useState(1);
  const [msg, setMsg] = useState('');
  const { user } = useAuth();
  const nav = useNavigate();

  useEffect(() => {
    api.get(`/v1/products/${id}`).then(r => setP(r.data)).catch(() => setP(null));
  }, [id]);

  const addToCart = async () => {
    if (!user) { nav('/login'); return; }
    await api.post('/v1/cart/items', { productId: p.id, quantity: qty });
    setMsg('Added to cart ✓');
  };

  if (!p) return <div className="container">Product not found.</div>;
  return (
    <div className="container detail">
      <img src={p.imageUrl || 'https://via.placeholder.com/400x300?text=No+Image'} alt={p.name} />
      <div>
        <h1>{p.name}</h1>
        <p className="brand">{p.brand} · {p.category?.name}</p>
        <p className="price big">${p.price}</p>
        {p.compareAtPrice && <p className="was">Was ${p.compareAtPrice}</p>}
        <p>{p.description}</p>
        <div className="buy-row">
          <input type="number" min="1" value={qty} onChange={e => setQty(+e.target.value)} />
          <button onClick={addToCart}>Add to cart</button>
        </div>
        {msg && <p className="ok">{msg}</p>}
      </div>
    </div>
  );
}
