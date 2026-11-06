// PROD-14a. Levanta el backend real (el jar) para la red de navegador.
//
// 1. Recrea la base `uvostore_e2e` (se niega con cualquier nombre que no termine en `_e2e`): cada corrida
//    empieza de cero y la base de desarrollo no se toca.
// 2. Lanza `java -jar target/uvo-store-*.jar` con un entorno propio y con el directorio de trabajo en un
//    temporal, para que spring-dotenv no lea el `.env` del backend (que apunta a la base de desarrollo).
//
// Las credenciales de PostgreSQL salen de E2E_DB_USERNAME / E2E_DB_PASSWORD; ver support/database.ts.
//
// Corre directo con Node (`node e2e/launch/backend.ts`); Playwright lo arranca como webServer.

import { spawn } from 'node:child_process'
import { createWriteStream, existsSync, mkdirSync, mkdtempSync, readdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import pg from 'pg'
import { dbConfig } from '../support/database.ts'
import {
  BACKEND_PORT,
  DOUBLES_HTTP_PORT,
  DOUBLES_SMTP_PORT,
  E2E_DB_NAME,
  E2E_ENCRYPTION_KEY,
  E2E_JWT_SECRET,
  E2E_PLATFORM_API_KEY,
  E2E_STRIPE_WEBHOOK_SECRET,
  VITE_PORT,
} from '../support/env.ts'

const FRONTEND_DIR = path.resolve(import.meta.dirname, '..', '..')
const REPO_DIR = path.resolve(FRONTEND_DIR, '..')
const LOG_DIR = path.join(FRONTEND_DIR, 'e2e', '.logs')

const { host: dbHost, port: dbPort, user: dbUser, password: dbPassword } = dbConfig

async function recreateDatabase(): Promise<void> {
  if (!/^[a-z0-9_]+_e2e$/.test(E2E_DB_NAME)) {
    throw new Error(`La base de la red tiene que llamarse *_e2e (se borra en cada corrida); llegó «${E2E_DB_NAME}»`)
  }
  const client = new pg.Client({ host: dbHost, port: dbPort, user: dbUser, password: dbPassword, database: 'postgres' })
  await client.connect()
  try {
    await client.query(`DROP DATABASE IF EXISTS "${E2E_DB_NAME}" WITH (FORCE)`)
    await client.query(`CREATE DATABASE "${E2E_DB_NAME}"`)
  } finally {
    await client.end()
  }
  console.log(`[backend] base ${E2E_DB_NAME} recreada en ${dbHost}:${dbPort}`)
}

function findJar(): string {
  const target = path.join(REPO_DIR, 'target')
  const jar = existsSync(target)
    ? readdirSync(target).find((name) => /^uvo-store-.*\.jar$/.test(name))
    : undefined
  if (!jar) {
    throw new Error('No está el jar del backend. Genéralo antes desde la raíz del repo: ./mvnw -o package -DskipTests')
  }
  return path.join(target, jar)
}

async function main(): Promise<void> {
  const jar = findJar()
  await recreateDatabase()

  const workDir = mkdtempSync(path.join(tmpdir(), 'uvostore-e2e-'))
  mkdirSync(LOG_DIR, { recursive: true })
  const log = createWriteStream(path.join(LOG_DIR, 'backend.log'))

  const env: NodeJS.ProcessEnv = {
    ...process.env,
    APP_ENVIRONMENT: 'development',
    SERVER_PORT: String(BACKEND_PORT),
    DB_URL: `jdbc:postgresql://${dbHost}:${dbPort}/${E2E_DB_NAME}?charSet=UTF8`,
    DB_USERNAME: dbUser,
    DB_PASSWORD: dbPassword,
    FLYWAY_USER: '',
    FLYWAY_PASSWORD: '',
    JWT_SECRET: E2E_JWT_SECRET,
    APP_ENCRYPTION_KEY: E2E_ENCRYPTION_KEY,
    PLATFORM_API_KEY: E2E_PLATFORM_API_KEY,
    PUBLIC_STOREFRONT_URL: `http://{host}:${VITE_PORT}`,
    PUBLIC_API_URL: `http://{host}:${BACKEND_PORT}`,
    APP_UPLOAD_DIR: path.join(workDir, 'uploads'),
    // El POS en el circuito: encendido, y el outbox despachando cada segundo para que la venta llegue pronto.
    POS_ENABLED: 'true',
    OUTBOX_INTERVAL_MS: '1000',
    // Los correos van al doble de SMTP, que no habla STARTTLS ni pide credenciales.
    MAIL_HOST: '127.0.0.1',
    MAIL_PORT: String(DOUBLES_SMTP_PORT),
    MAIL_SMTP_AUTH: 'false',
    MAIL_SMTP_STARTTLS: 'false',
    MAIL_USERNAME: '',
    MAIL_PASSWORD: '',
    // Un solo cliente (127.0.0.1) hace todos los logins de la corrida; los límites por IP no aportan aquí.
    RATE_LIMIT_ENABLED: 'false',
    // GAP-08. Las tres pasarelas van a sus dobles (frontend/e2e/doubles/gateways.ts). El backend solo lo acepta
    // con APP_ENVIRONMENT=development.
    STRIPE_API_BASE: `http://127.0.0.1:${DOUBLES_HTTP_PORT}/stripe`,
    WEBPAY_API_BASE: `http://127.0.0.1:${DOUBLES_HTTP_PORT}/webpay`,
    MERCADOPAGO_API_BASE: `http://127.0.0.1:${DOUBLES_HTTP_PORT}/mercadopago`,
    STRIPE_WEBHOOK_SECRET: E2E_STRIPE_WEBHOOK_SECRET,
    // GAP-08. La conciliación encendida para el retorno perdido de Webpay: mira las órdenes con pago pendiente
    // de más de un minuto, así que no se adelanta a los webhooks que mandan los otros casos. Las de pago manual
    // no las mira.
    RECONCILIATION_ENABLED: 'true',
    RECONCILIATION_INTERVAL_MS: '5000',
    RECONCILIATION_AFTER_MINUTES: '1',
    // Los demás trabajos programados no forman parte del circuito y solo meterían ruido.
    POS_RECONCILIATION_ENABLED: 'false',
    ABANDONED_ORDERS_ENABLED: 'false',
    STOCK_RECOVERY_ENABLED: 'false',
    SENTRY_DSN: '',
    METRICS_TOKEN: '',
  }

  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java'
  console.log(`[backend] ${path.basename(jar)} en el puerto ${BACKEND_PORT} (log: e2e/.logs/backend.log)`)
  console.log(`[backend] POS en http://127.0.0.1:${DOUBLES_HTTP_PORT}, SMTP en 127.0.0.1:${DOUBLES_SMTP_PORT}`)
  const child = spawn(java, ['-jar', jar], { cwd: workDir, env, stdio: ['ignore', 'pipe', 'pipe'] })
  child.stdout.pipe(log)
  child.stderr.pipe(log)

  // Si Playwright corta el lanzador, el java no puede quedar huérfano ocupando el puerto.
  const stop = () => {
    if (child.exitCode === null) child.kill()
  }
  process.on('SIGINT', stop)
  process.on('SIGTERM', stop)
  process.on('exit', stop)
  child.on('exit', (code) => {
    console.log(`[backend] terminó con código ${code}`)
    process.exit(code ?? 0)
  })
}

main().catch((err: unknown) => {
  console.error(`[backend] ${err instanceof Error ? err.message : String(err)}`)
  process.exit(1)
})
