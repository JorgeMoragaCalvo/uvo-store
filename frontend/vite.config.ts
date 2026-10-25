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

  // PROD-14a: the store comes from the browser's subdomain when it has one. With a fixed Host,
  // "tienda-b.localhost:5173" reached the backend as the target's store, so only one store could be
  // used through Vite. A bare "localhost" (or 127.0.0.1) still goes to the target, as before.
  function backendHost(browserHost: string | undefined): string {
    const slug = /^([a-z0-9-]+)\.localhost(:\d+)?$/i.exec(browserHost ?? '')?.[1]
    return slug ? `${slug.toLowerCase()}.localhost${target.port ? `:${target.port}` : ''}` : target.host
  }

  return {
    plugins: [react(), tailwindcss()],
    build: {
      // A6: the routes themselves are split in router.tsx; this only pulls React and the router out
      // of the entry chunk. They change on a dependency bump, not on every deploy, so keeping them
      // apart lets a returning visitor reuse them from cache while the app code churns.
      // Deliberately one group only — aggressive manual grouping buys little and risks
      // module-initialisation order problems.
      rolldownOptions: {
        output: {
          advancedChunks: {
            groups: [
              { name: 'react-vendor', test: /node_modules[\\/](react|react-dom|react-router|react-router-dom)[\\/]/ },
            ],
          },
        },
      },
    },
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
          configure: (proxy) => {
            proxy.on('proxyReq', (proxyReq, req) => {
              proxyReq.setHeader('host', backendHost(req.headers.host))
            })
          },
        },
      },
    },
  }
})
