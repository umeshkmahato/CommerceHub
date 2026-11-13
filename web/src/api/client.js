import axios from 'axios';

/**
 * Central API client — ALL backend calls go through here.
 *
 * Token lifecycle:
 *  - accessToken in memory + localStorage (survives refresh, XSS caveat
 *    documented; httpOnly cookie refresh is the production upgrade).
 *  - 401 → try refresh once → retry original request → else logout.
 *  - Concurrent 401s share ONE refresh call (promise dedupe) — otherwise
 *    N parallel requests would each burn a refresh token rotation.
 */
const api = axios.create({ baseURL: '/api', timeout: 10000 });

let accessToken = localStorage.getItem('ss_access') || null;
let refreshPromise = null;

export function setAccessToken(t) {
  accessToken = t;
  if (t) localStorage.setItem('ss_access', t);
  else localStorage.removeItem('ss_access');
}
export function getAccessToken() { return accessToken; }

api.interceptors.request.use(config => {
  if (accessToken) config.headers.Authorization = `Bearer ${accessToken}`;
  return config;
});

api.interceptors.response.use(
  res => res,
  async error => {
    const original = error.config;
    if (error.response?.status === 401 && !original._retried
        && !original.url?.includes('/auth/')) {
      original._retried = true;
      try {
        const token = await refreshAccessToken();
        original.headers.Authorization = `Bearer ${token}`;
        return api(original);
      } catch {
        setAccessToken(null);
        localStorage.removeItem('ss_refresh');
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  }
);

async function refreshAccessToken() {
  if (refreshPromise) return refreshPromise;   // dedupe concurrent refreshes
  const refresh = localStorage.getItem('ss_refresh');
  if (!refresh) throw new Error('no refresh token');
  refreshPromise = axios.post('/api/v1/auth/refresh', { refreshToken: refresh })
    .then(({ data }) => {
      setAccessToken(data.accessToken);
      localStorage.setItem('ss_refresh', data.refreshToken);  // rotated
      return data.accessToken;
    })
    .finally(() => { refreshPromise = null; });
  return refreshPromise;
}

export function storeTokens(data) {
  setAccessToken(data.accessToken);
  localStorage.setItem('ss_refresh', data.refreshToken);
}

export default api;
