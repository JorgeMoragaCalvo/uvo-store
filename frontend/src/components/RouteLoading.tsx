import Loading from './Loading'

// GAP-20. Lo que pinta el router (`HydrateFallback` en router.tsx) mientras baja el chunk de una ruta con carga
// diferida en la primera carga. Sin esto la página quedaba en blanco.

// A página completa: rutas de primer nivel (panel, plataforma), donde no hay layout alrededor.
export function PageLoading() {
  return (
    <div className="flex min-h-screen items-center justify-center">
      <Loading size="lg" />
    </div>
  )
}

// Dentro de la tienda: el header y el pie ya están, solo falta el contenido.
export function ContentLoading() {
  return (
    <div className="flex min-h-[50vh] items-center justify-center">
      <Loading size="lg" />
    </div>
  )
}
