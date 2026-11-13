import React, { useEffect, useState } from 'react';
import api from '../api/client.js';

/** Admin dashboard — product listing across all statuses (X-User-Roles: ADMIN). */
export default function AdminPage() {
  const [products, setProducts] = useState([]);
  const [error, setError] = useState('');

  useEffect(() => {
    api.get('/v1/admin/products', { params: { page: 0, size: 50 } })
      .then(r => setProducts(r.data.content))
      .catch(e => setError(e.response?.data?.message || 'Admin access required'));
  }, []);

  const deactivate = async (id) => {
    await api.patch(`/v1/admin/products/${id}/status`, { status: 'DISCONTINUED' });
    setProducts(ps => ps.map(p => p.id === id ? { ...p, status: 'DISCONTINUED' } : p));
  };

  return (
    <div className="container">
      <h2>Admin — products</h2>
      {error && <div className="error">{error}</div>}
      <table>
        <thead><tr><th>SKU</th><th>Name</th><th>Price</th><th>Status</th><th /></tr></thead>
        <tbody>
          {products.map(p => (
            <tr key={p.id}>
              <td>{p.sku}</td><td>{p.name}</td><td>${p.price}</td><td>{p.status}</td>
              <td>{p.status === 'ACTIVE' &&
                <button className="danger" onClick={() => deactivate(p.id)}>Deactivate</button>}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
