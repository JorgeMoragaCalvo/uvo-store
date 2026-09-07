import { useCartStore } from '../../stores/useCartStore'
import type { CartLine } from '../../stores/useCartStore'
import { formatCurrency } from '../../utils/currency'

export default function CartLineItem({ line }: { line: CartLine }) {
  const updateQuantity = useCartStore((state) => state.updateQuantity)
  const removeItem = useCartStore((state) => state.removeItem)

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

  return (
    <div className="flex gap-3 border-b border-gray-100 py-3">
      <div className="h-16 w-16 shrink-0 overflow-hidden rounded bg-gray-100">
        {image && <img src={image} alt={line.product.name} loading="lazy" className="h-full w-full object-cover" />}
      </div>

      <div className="flex flex-1 flex-col gap-1">
        <p className="text-sm font-medium text-dark">{line.product.name}</p>
        {attributesLabel && <p className="text-xs text-secondary">{attributesLabel}</p>}

        <div className="mt-auto flex items-center justify-between">
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => updateQuantity(line.id, line.type, Math.max(1, line.quantity - 1))}
              className="h-6 w-6 rounded border border-gray-300 text-sm leading-none"
            >
              −
            </button>
            <span className="w-6 text-center text-sm">{line.quantity}</span>
            <button
              type="button"
              onClick={() => updateQuantity(line.id, line.type, line.quantity + 1)}
              className="h-6 w-6 rounded border border-gray-300 text-sm leading-none"
            >
              +
            </button>
          </div>

          <div className="flex items-center gap-3">
            <span className="text-sm font-semibold text-dark">{formatCurrency(unitPrice * line.quantity)}</span>
            <button
              type="button"
              onClick={() => removeItem(line.id, line.type)}
              className="text-xs text-secondary hover:text-red-600"
              title="Eliminar"
            >
              ✕
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
