import { isRouteErrorResponse, Link, useRouteError } from 'react-router-dom'

// PROD-24b. El `errorElement` de las rutas. Sin él, un error al cargar una página (un chunk que ya no
// existe tras un despliegue, una excepción al renderizar) mostraba la pantalla por defecto de React
// Router, en inglés y con la traza. `home` es a dónde volver: la tienda o el panel.
export default function RouteError({ home = '/' }: { home?: string }) {
  const error = useRouteError()
  const notFound = isRouteErrorResponse(error) && error.status === 404

  return (
    <div className="mx-auto max-w-lg p-8 py-16 text-center">
      <h1 className="mb-2 text-2xl font-semibold text-dark">{notFound ? 'Página no encontrada' : 'Algo salió mal'}</h1>
      <p role="alert" className="mb-6 text-secondary">
        {notFound
          ? 'La dirección no existe o la página se movió.'
          : 'No pudimos mostrar esta página. Recargarla suele resolverlo; si sigue pasando, inténtalo en unos minutos.'}
      </p>
      <div className="flex flex-wrap justify-center gap-3">
        {!notFound && (
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="rounded bg-primary px-4 py-2 text-sm font-medium text-white"
          >
            Recargar
          </button>
        )}
        <Link to={home} className="rounded border border-gray-400 px-4 py-2 text-sm font-medium text-dark">
          Volver al inicio
        </Link>
      </div>
    </div>
  )
}
