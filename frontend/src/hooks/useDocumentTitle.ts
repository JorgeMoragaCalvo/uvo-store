import { useEffect } from 'react'
import { useStoreSettingsStore } from '../stores/useStoreSettingsStore'

// PROD-24b. Cada página con su título: antes todas se llamaban «frontend» (el de index.html), que es lo
// primero que lee un lector de pantalla al cambiar de página y lo que se ve en la pestaña y el historial.

/** Título con un sufijo fijo; lo usa el panel, que no carga los ajustes públicos de la tienda. */
export function useDocumentTitle(title: string | null | undefined, suffix: string) {
  useEffect(() => {
    document.title = title ? `${title} · ${suffix}` : suffix
  }, [title, suffix])
}

/**
 * Título de una página de la tienda: «Página · Tienda». Sin título de página (el inicio) se usa el título
 * SEO que el comerciante configuró, o el nombre de la tienda.
 */
export function useStoreTitle(title?: string | null) {
  const storeName = useStoreSettingsStore((state) => state.settings?.storeName)
  const metaTitle = useStoreSettingsStore((state) => state.settings?.metaTitle)

  useEffect(() => {
    if (!storeName) return
    document.title = title ? `${title} · ${storeName}` : metaTitle || storeName
  }, [title, storeName, metaTitle])
}
