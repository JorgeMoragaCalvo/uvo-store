import { useCartStore } from '../../stores/useCartStore'
import { formatCurrency } from '../../utils/currency'

// Cart.tsx and Checkout.tsx used to render this block separately, and both got the same two things
// wrong — so it lives in one place now.
//
// 1. Tax. With prices_include_tax the tax is already inside the subtotal, but listing it in the same
//    column as Subtotal and Total reads as a sum: 79.980 + 12.770 = 92.750, which is neither the
//    total nor 19% of anything on screen (the tax on a gross price is 19/119 of it, ~15,97%). Shown
//    below the total as an informational note instead. With prices_include_tax=false the tax IS
//    additive and stays a normal line.
// 2. Shipping. "No disponible" was being announced before the customer had been asked where they
//    live — shippingAvailable is false simply because no region has been chosen yet. That wording is
//    now reserved for a destination that really has no coverage.
export default function OrderSummary() {
  const totals = useCartStore((state) => state.totals)
  const region = useCartStore((state) => state.region)

  const destinationChosen = region !== ''
  const taxIncluded = totals.pricesIncludeTax && totals.taxAmount > 0

  function shippingLabel() {
    if (totals.shippingCost > 0) return formatCurrency(totals.shippingCost)
    if (!destinationChosen) return 'Se calcula al elegir la dirección'
    if (!totals.shippingAvailable) return 'No disponible'
    return 'Gratis'
  }

  return (
    <>
      <div className="flex flex-col gap-2 text-sm text-secondary">
        <div className="flex justify-between">
          <span>Subtotal</span>
          <span>{formatCurrency(totals.subtotal)}</span>
        </div>

        {/* A store that doesn't ship at all has no shipping line to show. */}
        {totals.shippingEnabled && (
          <div className="flex justify-between">
            <span>Envío</span>
            <span>{shippingLabel()}</span>
          </div>
        )}

        {totals.discountAmount > 0 && (
          <div className="flex justify-between text-green-700">
            <span>Descuento</span>
            <span>-{formatCurrency(totals.discountAmount)}</span>
          </div>
        )}

        {/* Only when the tax is genuinely added on top of the subtotal. */}
        {!totals.pricesIncludeTax && totals.taxAmount > 0 && (
          <div className="flex justify-between">
            <span>Impuesto</span>
            <span>{formatCurrency(totals.taxAmount)}</span>
          </div>
        )}
      </div>

      <div className="mt-3 flex justify-between border-t border-gray-100 pt-3 font-semibold text-dark">
        <span>Total</span>
        <span>{formatCurrency(totals.total)}</span>
      </div>

      {taxIncluded && (
        <p className="mt-1 text-xs text-secondary">IVA incluido: {formatCurrency(totals.taxAmount)}</p>
      )}
    </>
  )
}
