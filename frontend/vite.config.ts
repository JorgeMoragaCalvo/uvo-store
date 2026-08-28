import path from 'node:path'
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = new URL(env.VITE_DEV_PROXY_TARGET || 'http://demo.localhost:8080')

  // Node's own DNS resolver can't resolve "<name>.localhost" hostnames on Windows even though
  // curl/browsers do (they rely on OS-level *.localhost handling that Node's resolver doesn't
  // pick up) — connect over 127.0.0.1 instead, but keep sending the original Host header so
  // TenantResolutionFilter on the backend still resolves the right store by subdomain.
  const connectHost = target.hostname === 'localhost' || target.hostname.endsWith('.localhost') ? '127.0.0.1' : target.hostname

  return {
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: {
        '@': path.resolve(import.meta.dirname, './src'),
      },
    },
    server: {
      proxy: {
        '/api': {
          target: `${target.protocol}//${connectHost}:${target.port}`,
          changeOrigin: false,
          headers: { host: target.host },
        },
      },
    },
  }
})
