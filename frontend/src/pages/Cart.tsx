import { Link } from 'react-router-dom'
import { useCartStore } from '../stores/useCartStore'
import CartLineItem from '../components/cart/CartLineItem'
import { formatCurrency } from '../utils/currency'

export default function Cart() {
  const items = useCartStore((state) => state.items)
  const totals = useCartStore((state) => state.totals)

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

  return (
    <div className="mx-auto grid max-w-5xl gap-8 p-4 py-8 lg:grid-cols-3">
      <div className="lg:col-span-2">
        <h1 className="mb-4 text-2xl font-semibold text-dark">Carrito de Compras</h1>
        <div className="rounded-lg border border-gray-100 px-4">
          {items.map((line) => (
            <CartLineItem key={`${line.type}-${line.id}`} line={line} />
          ))}
        </div>
      </div>

      <div className="h-fit rounded-lg border border-gray-100 p-4">
        <h2 className="mb-3 font-semibold text-dark">Resumen del Pedido</h2>
        <div className="flex flex-col gap-2 text-sm text-secondary">
          <div className="flex justify-between">
            <span>Subtotal</span>
            <span>{formatCurrency(totals.subtotal)}</span>
          </div>
          <div className="flex justify-between">
            <span>Envío</span>
            <span>{totals.shippingCost > 0 ? formatCurrency(totals.shippingCost) : 'Gratis'}</span>
          </div>
          {totals.taxAmount > 0 && (
            <div className="flex justify-between">
              <span>Impuesto</span>
              <span>{formatCurrency(totals.taxAmount)}</span>
            </div>
          )}
          {totals.discountAmount > 0 && (
            <div className="flex justify-between text-green-600">
              <span>Descuento</span>
              <span>-{formatCurrency(totals.discountAmount)}</span>
            </div>
          )}
        </div>

        <div className="mt-3 flex justify-between border-t border-gray-100 pt-3 font-semibold text-dark">
          <span>Total</span>
          <span>{formatCurrency(totals.total)}</span>
        </div>

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
