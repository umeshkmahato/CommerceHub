import React, { useEffect, useState, useCallback } from 'react';
import { Link } from 'react-router-dom';
import api from '../api/client.js';

/** Storefront listing: search, price filter, pagination, sorting. */
export default function ProductListPage() {
  const [q, setQ] = useState('');
  const [min, setMin] = useState('');
  const [max, setMax] = useState('');
  const [sort, setSort] = useState('name');
  const [page, setPage] = useState(0);
  const [data, setData] = useState({ content: [], totalPages: 0 });
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const { data } = await api.get('/v1/products', {
        params: { q: q || undefined, minPrice: min || undefined,
                  maxPrice: max || undefined, sort, page, size: 12 }
      });
      setData(data);
    } finally { setLoading(false); }
  }, [q, min, max, sort, page]);

  useEffect(() => { const t = setTimeout(load, 300); return () => clearTimeout(t); }, [load]);

  return (
    <div className="container">
      <div className="filters">
        <input placeholder="Search products…" value={q} onChange={e => { setQ(e.target.value); setPage(0); }} />
        <input placeholder="Min $" value={min} onChange={e => { setMin(e.target.value); setPage(0); }} />
        <input placeholder="Max $" value={max} onChange={e => { setMax(e.target.value); setPage(0); }} />
        <select value={sort} onChange={e => setSort(e.target.value)}>
          <option value="name">Name</option>
          <option value="price_asc">Price ↑</option>
          <option value="price_desc">Price ↓</option>
          <option value="newest">Newest</option>
        </select>
      </div>
      {loading ? <p>Loading…</p> : (
        <div className="grid">
          {data.content.map(p => (
            <Link key={p.id} to={`/products/${p.id}`} className="card">
              <img src={p.imageUrl || 'https://via.placeholder.com/300x200?text=No+Image'} alt={p.name} />
              <h3>{p.name}</h3>
              <p className="brand">{p.brand}</p>
              <p className="price">${p.price}</p>
            </Link>
          ))}
        </div>
      )}
      <div className="pager">
        <button disabled={page === 0} onClick={() => setPage(p => p - 1)}>← Prev</button>
        <span>Page {page + 1} / {data.totalPages || 1}</span>
        <button disabled={page + 1 >= data.totalPages} onClick={() => setPage(p => p + 1)}>Next →</button>
      </div>
    </div>
  );
}
