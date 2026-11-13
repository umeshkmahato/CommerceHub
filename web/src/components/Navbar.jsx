import React from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext.jsx';

export default function Navbar() {
  const { user, logout, isAdmin } = useAuth();
  const nav = useNavigate();
  return (
    <nav className="navbar">
      <Link to="/" className="logo">CommerceHub</Link>
      <div className="nav-links">
        <Link to="/">Products</Link>
        {user && <Link to="/cart">Cart</Link>}
        {user && <Link to="/orders">Orders</Link>}
        {isAdmin && <Link to="/admin">Admin</Link>}
        {user ? (
          <>
            <span className="user">{user.firstName}</span>
            <button onClick={() => { logout(); nav('/'); }}>Logout</button>
          </>
        ) : (
          <>
            <Link to="/login">Login</Link>
            <Link to="/register">Register</Link>
          </>
        )}
      </div>
    </nav>
  );
}
