import { useEffect, useRef, type RefObject } from 'react'
import { useLocation } from 'react-router-dom'

/**
 * GAP-19. En una SPA, seguir un enlace no recarga la página: el foco quedaba en `body` y el lector de pantalla no
 * decía nada de la página nueva. Al cambiar de ruta, el foco va al elemento indicado (el `<main>`, con
 * `tabIndex={-1}`), así el lector anuncia el contenido y el siguiente Tab empieza ahí y no en el encabezado.
 *
 * No en la primera carga (el navegador ya pone el foco al principio) ni cuando cambia solo la query: los filtros de
 * la tienda y la búsqueda la cambian mientras se escribe, y sacar el foco del campo sería peor. No mueve el scroll.
 */
export function useFocusOnRouteChange(target: RefObject<HTMLElement | null>): void {
  const { pathname } = useLocation()
  const previous = useRef(pathname)

  useEffect(() => {
    if (previous.current === pathname) return
    previous.current = pathname
    target.current?.focus({ preventScroll: true })
  }, [pathname, target])
}
