/**
 * IMP-04. Una pestaña abierta antes de un despliegue tiene el index.html viejo, y al navegar a una página lazy pide
 * un chunk que quizá ya no existe (nginx responde 404, ops/nginx/uvostore-locations.conf). publicar-frontend.sh
 * conserva los chunks de las versiones anteriores, pero si aun así falta uno, recargar trae el index.html nuevo
 * y con él los chunks vigentes.
 *
 * Una sola vez por ventana de tiempo: si el chunk falta también después de recargar (el despliegue está roto, o
 * no hay red), recargar en bucle no ayuda; entonces se muestra RouteError con su botón «Recargar».
 */

const RELOAD_KEY = 'uvostore.staleChunkReloadAt'
const RELOAD_WINDOW_MS = 60_000

// Chromium, Firefox y Safari, y el preload de CSS de Vite.
const STALE_CHUNK_MESSAGES = [
  /Failed to fetch dynamically imported module/i,
  /error loading dynamically imported module/i,
  /Importing a module script failed/i,
  /Unable to preload CSS/i,
]

export function isStaleChunkError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : typeof error === 'string' ? error : ''
  return STALE_CHUNK_MESSAGES.some((pattern) => pattern.test(message))
}

/**
 * Si todavía se puede recargar sola. Sin sessionStorage (bloqueado, modo privado estricto) no: sin la marca no
 * habría cómo cortar un bucle.
 */
export function canReloadForStaleChunk(now = Date.now(), storage: Storage | null = sessionStorageOrNull()): boolean {
  if (!storage) return false
  try {
    const last = Number(storage.getItem(RELOAD_KEY))
    return !(last > 0 && now - last < RELOAD_WINDOW_MS)
  } catch {
    return false
  }
}

/** Deja la marca y recarga. Devuelve false (y no recarga) si no se pudo dejar la marca. */
export function reloadForStaleChunk(
  reload: () => void = () => window.location.reload(),
  now = Date.now(),
  storage: Storage | null = sessionStorageOrNull(),
): boolean {
  if (!storage) return false
  try {
    storage.setItem(RELOAD_KEY, String(now))
  } catch {
    return false
  }
  reload()
  return true
}

/** Las dos cosas juntas, para el evento vite:preloadError de main.tsx. */
export function reloadOnceForStaleChunk(): boolean {
  return canReloadForStaleChunk() && reloadForStaleChunk()
}

function sessionStorageOrNull(): Storage | null {
  try {
    return window.sessionStorage
  } catch {
    return null
  }
}
