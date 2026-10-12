/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

// In development the API is reached through the local gateway (docker compose, port 80),
// so the browser sees one origin exactly like in production.
const gateway = process.env.GATEWAY_URL ?? 'http://localhost';

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { '@': path.resolve(__dirname, 'src') } },
  server: {
    port: 5173,
    proxy: {
      // The browser talks to the dev server's origin; drop Origin so the API sees a same-origin request
      // (otherwise Spring's CORS filter rejects state-changing calls from localhost:5173).
      '/api': { target: gateway, changeOrigin: true, configure: (proxy) => proxy.on('proxyReq', (req) => req.removeHeader('origin')) },
      '/analytics': { target: gateway, changeOrigin: true, configure: (proxy) => proxy.on('proxyReq', (req) => req.removeHeader('origin')) },
    },
  },
  build: {
    sourcemap: false,
    chunkSizeWarningLimit: 1500,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test-setup.ts'],
    css: false,
  },
});
