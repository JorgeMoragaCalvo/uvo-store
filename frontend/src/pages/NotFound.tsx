import { Link } from 'react-router-dom'
import { useStoreTitle } from '../hooks/useDocumentTitle'

// PROD-24b. Una URL que no existe mostraba la página de error de React Router, en inglés y sin la tienda
// alrededor. Ahora queda dentro del layout, con salida a la tienda.
export default function NotFound() {
  useStoreTitle('Página no encontrada')

  return (
    <div className="mx-auto max-w-lg p-8 py-16 text-center">
      <h1 className="mb-2 text-2xl font-semibold text-dark">Página no encontrada</h1>
      <p className="mb-6 text-secondary">La dirección no existe o la página se movió.</p>
      <div className="flex flex-wrap justify-center gap-3">
        <Link to="/" className="rounded border border-gray-400 px-4 py-2 text-sm font-medium text-dark">
          Ir al inicio
        </Link>
        <Link to="/shop" className="rounded bg-primary px-4 py-2 text-sm font-medium text-white">
          Ver productos
        </Link>
      </div>
    </div>
  )
}
