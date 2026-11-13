import React, { createContext, useContext, useEffect, useState, useCallback } from 'react';
import api, { getAccessToken, setAccessToken, storeTokens } from '../api/client.js';

/**
 * Auth context — single source of truth for login state.
 * Hydrates from stored token + /users/me on mount; exposes login/register/
 * logout to the whole app via useAuth().
 */
const AuthCtx = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(!!getAccessToken());

  useEffect(() => {
    if (!getAccessToken()) { setLoading(false); return; }
    api.get('/v1/users/me')
      .then(r => setUser(r.data))
      .catch(() => { setAccessToken(null); localStorage.removeItem('ss_refresh'); })
      .finally(() => setLoading(false));
  }, []);

  const login = useCallback(async (email, password) => {
    const { data } = await api.post('/v1/auth/login', { email, password });
    storeTokens(data);
    const me = await api.get('/v1/users/me');
    setUser(me.data);
    return me.data;
  }, []);

  const register = useCallback(async (payload) => {
    const { data } = await api.post('/v1/auth/register', payload);
    storeTokens(data);
    const me = await api.get('/v1/users/me');
    setUser(me.data);
    return me.data;
  }, []);

  const logout = useCallback(() => {
    const refresh = localStorage.getItem('ss_refresh');
    if (refresh) api.post('/v1/auth/logout', { refreshToken: refresh }).catch(() => {});
    setAccessToken(null);
    localStorage.removeItem('ss_refresh');
    setUser(null);
  }, []);

  const hasRole = useCallback((role) =>
    user?.roles?.includes(role) ?? false, [user]);

  return (
    <AuthCtx.Provider value={{ user, loading, login, register, logout, hasRole, isAdmin: hasRole('ADMIN') }}>
      {children}
    </AuthCtx.Provider>
  );
}

export const useAuth = () => useContext(AuthCtx);
