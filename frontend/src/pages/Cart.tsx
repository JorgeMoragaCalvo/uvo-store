import { useEffect } from 'react'
import { Link } from 'react-router-dom'
import { useCartStore } from '../stores/useCartStore'
import OrderSummary from '../components/cart/OrderSummary'
import CartLineItem from '../components/cart/CartLineItem'
import { useStoreTitle } from '../hooks/useDocumentTitle'

export default function Cart() {
  const items = useCartStore((state) => state.items)
  const lineErrors = useCartStore((state) => state.lineErrors)
  const totalsError = useCartStore((state) => state.totalsError)
  const validateCart = useCartStore((state) => state.validateCart)
  useStoreTitle('Carrito')

  // PROD-24b. El carrito sale de localStorage y puede tener días: al abrirlo se pregunta por el stock y la
  // disponibilidad de cada línea, para que el comprador no se entere recién al confirmar.
  useEffect(() => {
    validateCart()
  }, [validateCart])

  if (items.length === 0) {
    return (
      <div className="mx-auto max-w-2xl p-8 text-center">
        <h1 className="mb-2 text-2xl font-semibold text-dark">Tu carrito está vacío</h1>
        <p className="mb-6 text-secondary">Agrega productos para verlos aquí.</p>
        <Link to="/shop" className="rounded bg-primary px-5 py-2.5 text-sm font-medium text-white">
          Ir a la tienda
        </Link>
      </div>
    )
  }

  const problems = Object.keys(lineErrors).length

  return (
    <div className="mx-auto grid max-w-5xl gap-8 p-4 py-8 lg:grid-cols-3">
      <div className="lg:col-span-2">
        <h1 className="mb-4 text-2xl font-semibold text-dark">Carrito de Compras</h1>
        <div role="status">
          {problems > 0 && (
            <p className="mb-3 rounded bg-red-50 p-3 text-sm text-red-700">
              {problems === 1
                ? 'Un producto necesita atención: ajusta la cantidad o quítalo para seguir.'
                : `${problems} productos necesitan atención: ajusta la cantidad o quítalos para seguir.`}
            </p>
          )}
        </div>
        <div className="rounded-lg border border-gray-100 px-4">
          {items.map((line) => (
            <CartLineItem key={`${line.type}-${line.id}`} line={line} />
          ))}
        </div>
      </div>

      <div className="h-fit rounded-lg border border-gray-100 p-4">
        <h2 className="mb-3 font-semibold text-dark">Resumen del Pedido</h2>
        {totalsError && (
          <p role="alert" className="mb-3 rounded bg-red-50 p-2 text-xs text-red-700">
            No pudimos actualizar los totales. Revisa tu conexión o tu carrito e inténtalo de nuevo.
          </p>
        )}
        <OrderSummary />

        <div className="mt-4 flex flex-col gap-2">
          <Link to="/checkout" className="rounded bg-primary px-4 py-2.5 text-center text-sm font-medium text-white">
            Finalizar Compra
          </Link>
          <Link to="/shop" className="rounded border border-gray-400 px-4 py-2.5 text-center text-sm font-medium text-dark">
            Seguir Comprando
          </Link>
        </div>
      </div>
    </div>
  )
}
