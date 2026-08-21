import { Link } from 'react-router-dom'
import { useCartStore } from '../../stores/useCartStore'
import CartLineItem from './CartLineItem'
import { formatCurrency } from '../../utils/currency'

export default function CartSidebar() {
  const isOpen = useCartStore((state) => state.isSidebarOpen)
  const close = useCartStore((state) => state.closeSidebar)
  const items = useCartStore((state) => state.items)
  const totals = useCartStore((state) => state.totals)

  if (!isOpen) return null

  return (
    <>
      <div className="fixed inset-0 z-50 bg-black/40" onClick={close} />

      <aside className="fixed right-0 top-0 z-50 flex h-full w-full max-w-sm flex-col bg-white shadow-xl">
        <div className="flex items-center justify-between border-b border-gray-100 p-4">
          <h2 className="text-lg font-semibold text-dark">Tu carrito</h2>
          <button type="button" onClick={close} className="text-secondary hover:text-dark">
            ✕
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-4">
          {items.length === 0 ? (
            <p className="py-8 text-center text-sm text-secondary">Tu carrito está vacío</p>
          ) : (
            items.map((line) => <CartLineItem key={`${line.type}-${line.id}`} line={line} />)
          )}
        </div>

        {items.length > 0 && (
          <div className="border-t border-gray-100 p-4">
            <div className="mb-3 flex items-center justify-between text-sm font-semibold text-dark">
              <span>Total</span>
              <span>{formatCurrency(totals.total)}</span>
            </div>
            <div className="flex flex-col gap-2">
              <Link
                to="/cart"
                onClick={close}
                className="rounded border border-primary px-4 py-2 text-center text-sm font-medium text-primary"
              >
                Ver carrito
              </Link>
              <Link
                to="/checkout"
                onClick={close}
                className="rounded bg-primary px-4 py-2 text-center text-sm font-medium text-white"
              >
                Finalizar Compra
              </Link>
            </div>
          </div>
        )}
      </aside>
    </>
  )
}
