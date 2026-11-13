import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider, useAuth } from './context/AuthContext.jsx';
import Navbar from './components/Navbar.jsx';
import LoginPage from './pages/LoginPage.jsx';
import RegisterPage from './pages/RegisterPage.jsx';
import ProductListPage from './pages/ProductListPage.jsx';
import ProductDetailPage from './pages/ProductDetailPage.jsx';
import CartPage from './pages/CartPage.jsx';
import CheckoutPage from './pages/CheckoutPage.jsx';
import OrdersPage from './pages/OrdersPage.jsx';
import AdminPage from './pages/AdminPage.jsx';

/** ProtectedRoute — redirects to /login when unauthenticated. */
function Protected({ children, admin = false }) {
  const { user, loading, isAdmin } = useAuth();
  if (loading) return <div className="center">Loading…</div>;
  if (!user) return <Navigate to="/login" replace />;
  if (admin && !isAdmin) return <Navigate to="/" replace />;
  return children;
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Navbar />
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/" element={<ProductListPage />} />
          <Route path="/products/:id" element={<ProductDetailPage />} />
          <Route path="/cart" element={<Protected><CartPage /></Protected>} />
          <Route path="/checkout" element={<Protected><CheckoutPage /></Protected>} />
          <Route path="/orders" element={<Protected><OrdersPage /></Protected>} />
          <Route path="/admin" element={<Protected admin><AdminPage /></Protected>} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}
