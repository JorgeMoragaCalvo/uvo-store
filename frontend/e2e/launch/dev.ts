// IMP-04. `npm run e2e:dev`: la suite sobre el servidor de desarrollo de Vite (sin nginx ni CSP). Fija
// E2E_MODE=vite y pasa el resto de argumentos a Playwright, igual en Windows que en Linux.
import { spawnSync } from 'node:child_process'
import path from 'node:path'

const cli = path.resolve(import.meta.dirname, '..', '..', 'node_modules', '@playwright', 'test', 'cli.js')
const result = spawnSync(process.execPath, [cli, 'test', ...process.argv.slice(2)], {
  stdio: 'inherit',
  env: { ...process.env, E2E_MODE: 'vite' },
})
process.exit(result.status ?? 1)
