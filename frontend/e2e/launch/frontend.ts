// IMP-04. El storefront de la modalidad `dist`: el build de producción servido por nginx con la configuración
// versionada en ops/nginx/, la misma que se despliega. Playwright lo arranca como webServer en VITE_PORT.
//
// 1. El build: E2E_FRONTEND_DIST si viene (un dist ya construido), si no `vite build` a e2e/.dist (no pisa
//    dist/). Con el DSN del doble de Sentry, así que su origen entra en la CSP del build como entraría el real.
// 2. Un nginx.conf en e2e/.logs/nginx/ que incluye ops/nginx/uvostore-locations.conf (copiado a uvostore/ junto
//    al nginx.conf, como en producción) y agrega lo único propio del E2E: /__gateway/ hacia los dobles, para que
//    el formulario de Webpay vaya al mismo origen y la CSP real lo deje pasar (e2e/doubles/gateways.ts).
// 3. `nginx -t` y luego nginx en primer plano.
//
// nginx: NGINX_BIN o el del PATH. En Linux, el paquete nginx; en Windows, el zip de nginx.org
// (docs/despliegue-frontend.md).

import { spawn, spawnSync } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import {
  BACKEND_PORT,
  DOUBLES_HTTP_PORT,
  E2E_SENTRY_ENVIRONMENT,
  E2E_SENTRY_RELEASE,
  NGINX_UPSTREAM_ADDRESS,
  SENTRY_DOUBLE_DSN,
  VITE_PORT,
} from '../support/env.ts'

const FRONTEND_DIR = path.resolve(import.meta.dirname, '..', '..')
const REPO_DIR = path.resolve(FRONTEND_DIR, '..')
const NGINX_SOURCE = path.join(REPO_DIR, 'ops', 'nginx')
const PREFIX = path.join(FRONTEND_DIR, 'e2e', '.logs', 'nginx')
const CONF = path.join(PREFIX, 'nginx.conf')
const NGINX = process.env.NGINX_BIN ?? 'nginx'

/** nginx entiende / en Windows; las \ las tomaría como escapes dentro de las comillas. */
const forNginx = (p: string) => p.replaceAll('\\', '/')

function build(): string {
  const given = process.env.E2E_FRONTEND_DIST
  if (given) {
    const dist = path.resolve(given)
    if (!existsSync(path.join(dist, 'index.html'))) throw new Error(`E2E_FRONTEND_DIST=${given} no tiene index.html`)
    console.log(`[frontend] dist ya construido: ${dist}`)
    return dist
  }
  const dist = path.join(FRONTEND_DIR, 'e2e', '.dist')
  console.log(`[frontend] vite build -> e2e/.dist (Sentry: doble, release ${E2E_SENTRY_RELEASE})`)
  // Las variables del proceso le ganan a frontend/.env (loadEnv de Vite).
  // El bin de Vite con este mismo node, sin shell: igual en Windows que en Linux.
  const vite = path.join(FRONTEND_DIR, 'node_modules', 'vite', 'bin', 'vite.js')
  const result = spawnSync(process.execPath, [vite, 'build', '--outDir', dist, '--emptyOutDir', '--logLevel', 'warn'], {
    cwd: FRONTEND_DIR,
    stdio: 'inherit',
    env: {
      ...process.env,
      VITE_SENTRY_DSN: SENTRY_DOUBLE_DSN,
      VITE_SENTRY_ENVIRONMENT: E2E_SENTRY_ENVIRONMENT,
      VITE_SENTRY_RELEASE: E2E_SENTRY_RELEASE,
    },
  })
  if (result.status !== 0) throw new Error(`vite build terminó con código ${result.status}`)
  return dist
}

/** Lo justo para lo que sirve el dist; en producción lo pone el mime.types de la distribución. */
const MIME_TYPES = `
    types {
        text/html                html;
        text/css                 css;
        text/javascript          js mjs;
        application/json         json;
        image/svg+xml            svg;
        image/png                png;
        image/jpeg               jpg jpeg;
        image/webp               webp;
        image/x-icon             ico;
        font/woff2               woff2;
        text/plain               txt;
    }`

function writeConfig(dist: string): void {
  rmSync(PREFIX, { recursive: true, force: true })
  for (const dir of ['logs', 'temp', 'uvostore']) mkdirSync(path.join(PREFIX, dir), { recursive: true })
  // Copias exactas de los archivos versionados: lo que se prueba es lo que se despliega.
  for (const file of ['uvostore-locations.conf', 'security-headers.conf']) {
    copyFileSync(path.join(NGINX_SOURCE, file), path.join(PREFIX, 'uvostore', file))
  }
  writeFileSync(
    CONF,
    `# Generado por e2e/launch/frontend.ts; no editar. La configuración real está en ops/nginx/.
worker_processes 1;
error_log logs/error.log warn;
pid logs/nginx.pid;

events {
    worker_connections 256;
}

http {
${MIME_TYPES}
    default_type application/octet-stream;
    access_log logs/access.log;
    client_body_temp_path temp/client_body;
    proxy_temp_path temp/proxy;
    fastcgi_temp_path temp/fastcgi;
    uwsgi_temp_path temp/uwsgi;
    scgi_temp_path temp/scgi;

    upstream uvostore_backend {
        server 127.0.0.1:${BACKEND_PORT};
        keepalive 16;
    }

    server {
        listen 127.0.0.1:${VITE_PORT};
        server_name localhost .localhost;
        root "${forNginx(dist)}";
        # La dirección propia del proxy, la que el backend tiene en RATE_LIMIT_TRUSTED_PROXIES (support/env.ts).
        proxy_bind ${NGINX_UPSTREAM_ADDRESS};
        # El upstream de este server, por nombre (ops/nginx/uvostore-locations.conf).
        set $uvostore_upstream uvostore_backend;

        include uvostore/uvostore-locations.conf;

        # SOLO E2E: las páginas de pago de los dobles, en el origen de la tienda (e2e/doubles/gateways.ts).
        location /__gateway/ {
            proxy_pass http://127.0.0.1:${DOUBLES_HTTP_PORT};
        }
    }
}
`,
  )
}

/** `-e`: sin él, nginx abre antes que nada el log compilado (/var/log/nginx/error.log), que sin root no puede. */
function nginxArgs(...extra: string[]): string[] {
  return ['-p', forNginx(PREFIX) + '/', '-c', forNginx(CONF), '-e', 'logs/error.log', ...extra]
}

function main(): void {
  const dist = build()
  writeConfig(dist)

  const check = spawnSync(NGINX, nginxArgs('-t'), { encoding: 'utf8' })
  if (check.error) {
    throw new Error(
      `No se pudo ejecutar «${NGINX}» (${check.error.message}). Instala nginx o pon NGINX_BIN; ` +
        'ver docs/despliegue-frontend.md. Sin nginx: npm run e2e:dev (modalidad Vite).',
    )
  }
  if (check.status !== 0) throw new Error(`nginx -t falló:\n${check.stderr}`)
  console.log(`[frontend] nginx en el puerto ${VITE_PORT} sirviendo ${dist} (conf: e2e/.logs/nginx/nginx.conf)`)

  const child = spawn(NGINX, nginxArgs('-g', 'daemon off;'), { stdio: 'inherit' })
  // En Windows matar el proceso maestro deja vivos a los workers con el puerto tomado: se pide a nginx que pare.
  const stop = () => {
    if (child.exitCode === null) {
      spawnSync(NGINX, nginxArgs('-s', 'stop'))
      child.kill()
    }
  }
  process.on('SIGINT', stop)
  process.on('SIGTERM', stop)
  process.on('exit', stop)
  child.on('exit', (code) => {
    console.log(`[frontend] nginx terminó con código ${code}`)
    process.exit(code ?? 0)
  })
}

try {
  main()
} catch (err: unknown) {
  console.error(`[frontend] ${err instanceof Error ? err.message : String(err)}`)
  process.exit(1)
}
