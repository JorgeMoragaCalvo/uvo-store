// PROD-14a / GAP-08. La conexión a la base de la red, para el lanzador (que la recrea) y para los tests que miran lo
// que el panel no muestra (el historial de una orden).
//
// Las credenciales de PostgreSQL salen de E2E_DB_USERNAME / E2E_DB_PASSWORD (la CI las pasa así). Si faltan, se
// toman DB_USERNAME / DB_PASSWORD del `.env` del backend, para que en local funcione sin configurar nada.

import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import pg from 'pg'
import { E2E_DB_NAME } from './env.ts'

const REPO_DIR = path.resolve(import.meta.dirname, '..', '..', '..')

function backendDotEnv(): Map<string, string> {
  const values = new Map<string, string>()
  const file = path.join(REPO_DIR, '.env')
  if (!existsSync(file)) return values
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const match = /^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/.exec(line)
    if (match) values.set(match[1], match[2].replace(/^["']|["']$/g, ''))
  }
  return values
}

const dotEnv = backendDotEnv()

export const dbConfig = {
  host: process.env.E2E_DB_HOST ?? 'localhost',
  port: Number(process.env.E2E_DB_PORT ?? 5432),
  user: process.env.E2E_DB_USERNAME ?? dotEnv.get('DB_USERNAME') ?? 'uvostore',
  password: process.env.E2E_DB_PASSWORD ?? dotEnv.get('DB_PASSWORD') ?? 'uvostore',
}

/** Una consulta contra la base de la red (nunca la de desarrollo: el nombre termina en `_e2e`). */
export async function queryE2e<T extends pg.QueryResultRow>(sql: string, params: unknown[] = []): Promise<T[]> {
  const client = new pg.Client({ ...dbConfig, database: E2E_DB_NAME })
  await client.connect()
  try {
    return (await client.query<T>(sql, params)).rows
  } finally {
    await client.end()
  }
}
