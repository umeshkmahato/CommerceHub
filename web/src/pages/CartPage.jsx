import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import api from '../api/client.js';

export default function CartPage() {
  const [cart, setCart] = useState(null);

  const load = () => api.get('/v1/cart').then(r => setCart(r.data));
  useEffect(() => { load().catch(() => setCart({ items: [], subtotal: 0 })); }, []);

  const setQty = async (productId, qty) => {
    await api.put(`/v1/cart/items/${productId}`, { quantity: qty });
    load();
  };
  const remove = async (productId) => {
    await api.delete(`/v1/cart/items/${productId}`);
    load();
  };

  if (!cart) return <div className="container">Loading cart…</div>;
  return (
    <div className="container">
      <h2>Your cart</h2>
      {cart.items.length === 0 && <p>Cart is empty. <Link to="/">Browse products</Link></p>}
      {cart.items.map(i => (
        <div key={i.id} className="cart-row">
          <div><strong>{i.productName}</strong><br /><small>{i.sku}</small></div>
          <div>${i.unitPrice}</div>
          <div>
            <button onClick={() => setQty(i.productId, i.quantity - 1)}>−</button>
            <span>{i.quantity}</span>
            <button onClick={() => setQty(i.productId, i.quantity + 1)}>+</button>
          </div>
          <div>${i.lineTotal}</div>
          <button className="danger" onClick={() => remove(i.productId)}>Remove</button>
        </div>
      ))}
      {cart.items.length > 0 && (
        <div className="cart-total">
          <strong>Subtotal: ${cart.subtotal}</strong>
          <Link to="/checkout"><button>Checkout</button></Link>
        </div>
      )}
    </div>
  );
}
