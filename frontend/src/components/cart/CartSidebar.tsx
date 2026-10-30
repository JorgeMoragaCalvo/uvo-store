import { useRef } from 'react'
import { Link } from 'react-router-dom'
import { useCartStore } from '../../stores/useCartStore'
import CartLineItem from './CartLineItem'
import { formatCurrency } from '../../utils/currency'
import { Sheet, SheetClose, SheetContent, SheetTitle } from '../ui/sheet'

// PROD-24b. Era un <aside> encima de un <div> clickeable: sin rol de diálogo, sin Escape, sin llevar el
// foco adentro ni devolverlo, y la página de atrás seguía recibiendo el Tab y el scroll. El Sheet de Radix
// (el mismo del menú del celular) trae todo eso.
export default function CartSidebar() {
  const isOpen = useCartStore((state) => state.isSidebarOpen)
  const close = useCartStore((state) => state.closeSidebar)
  const items = useCartStore((state) => state.items)
  const totals = useCartStore((state) => state.totals)
  // Radix devuelve el foco a su propio Trigger, y este diálogo no tiene: lo abre el botón del header (o
  // «Agregar al carrito»), que viven en otro componente. Sin esto, al cerrar el foco caía al <body>.
  const returnFocusRef = useRef<HTMLElement | null>(null)

  return (
    <Sheet open={isOpen} onOpenChange={(open) => !open && close()}>
      <SheetContent
        side="right"
        showCloseButton={false}
        aria-describedby={undefined}
        onOpenAutoFocus={() => {
          returnFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
        }}
        onCloseAutoFocus={(event) => {
          event.preventDefault()
          returnFocusRef.current?.focus()
        }}
        className="w-full gap-0 bg-white p-0 sm:max-w-sm"
      >
        <div className="flex items-center justify-between border-b border-gray-100 p-4">
          <SheetTitle className="text-lg font-semibold text-dark">Tu carrito</SheetTitle>
          <SheetClose
            aria-label="Cerrar carrito"
            className="-mr-2 flex size-10 items-center justify-center text-secondary hover:text-dark"
          >
            <span aria-hidden="true">✕</span>
          </SheetClose>
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
      </SheetContent>
    </Sheet>
  )
}
