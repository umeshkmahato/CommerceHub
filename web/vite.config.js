import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev proxy: browser calls /api/* → gateway :8080. In Docker the nginx
// container serves the built app and proxies /api to the gateway service.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true }
    }
  }
});
