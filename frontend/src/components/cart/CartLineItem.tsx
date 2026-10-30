import { cartLineKey, useCartStore } from '../../stores/useCartStore'
import type { CartLine } from '../../stores/useCartStore'
import { formatCurrency } from '../../utils/currency'

export default function CartLineItem({ line }: { line: CartLine }) {
  const updateQuantity = useCartStore((state) => state.updateQuantity)
  const removeItem = useCartStore((state) => state.removeItem)
  // PROD-24b. Lo que validateCart() supo del backend: si la línea tiene un problema (sin stock, ya no está
  // a la venta) y cuánto se puede pedir como máximo.
  const lineError = useCartStore((state) => state.lineErrors[cartLineKey(line)])
  const maxQuantity = useCartStore((state) => state.maxQuantities[cartLineKey(line)])

  const image = line.variation?.image ?? line.product.featuredImage
  // M5: product.price is null for a variable product — it carries its price on each variation — so
  // this only resolves to null for a line that has neither, which the cart never creates. The
  // fallback keeps the row rendering instead of printing NaN if one ever slips through; the totals
  // shown below the list come from the backend, not from here.
  const unitPrice = line.variation?.price ?? line.product.price ?? 0
  const attributesLabel = line.variation
    ? Object.entries(line.variation.attributes)
        .map(([key, value]) => `${key}: ${value}`)
        .join(', ')
    : null
  const name = attributesLabel ? `${line.product.name} (${attributesLabel})` : line.product.name
  const atMax = maxQuantity !== undefined && line.quantity >= maxQuantity

  return (
    <div className="flex gap-3 border-b border-gray-100 py-3">
      <div className="h-16 w-16 shrink-0 overflow-hidden rounded bg-gray-100">
        {/* El nombre está al lado: repetirlo como alt lo haría leer dos veces. */}
        {image && <img src={image} alt="" loading="lazy" className="h-full w-full object-cover" />}
      </div>

      <div className="flex flex-1 flex-col gap-1">
        <p className="text-sm font-medium text-dark">{line.product.name}</p>
        {attributesLabel && <p className="text-xs text-secondary">{attributesLabel}</p>}
        {lineError && (
          <p className="text-xs text-red-700">
            {lineError}
          </p>
        )}

        <div className="mt-auto flex items-center justify-between">
          <div className="flex items-center gap-2" role="group" aria-label={`Cantidad de ${name}`}>
            <button
              type="button"
              onClick={() => updateQuantity(line.id, line.type, Math.max(1, line.quantity - 1))}
              disabled={line.quantity <= 1}
              aria-label={`Quitar uno de ${name}`}
              className="size-9 rounded border border-gray-400 text-sm leading-none disabled:opacity-40 sm:size-7"
            >
              −
            </button>
            <span className="w-6 text-center text-sm" aria-live="polite">
              {line.quantity}
            </span>
            <button
              type="button"
              onClick={() => updateQuantity(line.id, line.type, line.quantity + 1)}
              disabled={atMax}
              aria-label={`Agregar uno de ${name}`}
              title={atMax ? 'No hay más stock disponible' : undefined}
              className="size-9 rounded border border-gray-400 text-sm leading-none disabled:opacity-40 sm:size-7"
            >
              +
            </button>
          </div>

          <div className="flex items-center gap-1">
            <span className="text-sm font-semibold text-dark">{formatCurrency(unitPrice * line.quantity)}</span>
            <button
              type="button"
              onClick={() => removeItem(line.id, line.type)}
              aria-label={`Eliminar ${name} del carrito`}
              className="-mr-2 flex size-9 items-center justify-center text-xs text-secondary hover:text-red-600"
            >
              <span aria-hidden="true">✕</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
