import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useCartStore } from '../stores/useCartStore'
import { useCheckoutStore } from '../stores/useCheckoutStore'
import { formatCurrency } from '../utils/currency'

const STEPS = ['Contacto', 'Dirección', 'Pago'] as const

export default function Checkout() {
  const navigate = useNavigate()
  const items = useCartStore((state) => state.items)
  const totals = useCartStore((state) => state.totals)

  const { config, customer, shippingAddress, paymentMethod, customerNotes, loading, error } = useCheckoutStore()
  const fetchConfig = useCheckoutStore((state) => state.fetchConfig)
  const setCustomer = useCheckoutStore((state) => state.setCustomer)
  const setShippingAddress = useCheckoutStore((state) => state.setShippingAddress)
  const setPaymentMethod = useCheckoutStore((state) => state.setPaymentMethod)
  const setCustomerNotes = useCheckoutStore((state) => state.setCustomerNotes)
  const processCheckout = useCheckoutStore((state) => state.processCheckout)

  const [step, setStep] = useState(0)

  useEffect(() => {
    fetchConfig()
  }, [fetchConfig])

  if (items.length === 0) {
    return (
      <div className="mx-auto max-w-lg p-8 text-center">
        <h1 className="mb-2 text-xl font-semibold text-dark">Tu carrito está vacío</h1>
        <Link to="/shop" className="text-primary underline">Ir a la tienda</Link>
      </div>
    )
  }

  const contactValid = customer.email && customer.firstName && customer.lastName && customer.phone
  const addressValid =
    shippingAddress.addressLine1 && shippingAddress.city && shippingAddress.state && shippingAddress.postalCode

  async function handleConfirm() {
    const result = await processCheckout()
    if (!result.success) return

    if (result.webpayForm) {
      submitWebpayForm(result.webpayForm.url, result.webpayForm.token)
      return
    }

    if (result.redirectUrl) {
      // Full-page navigation to an external payment page (Stripe/MercadoPago), not a React-managed value.
      // eslint-disable-next-line react-hooks/immutability
      window.location.href = result.redirectUrl
      return
    }

    navigate(`/order-success?order=${result.orderNumber}`)
  }

  // Webpay Plus's redirect isn't a simple GET like Stripe/MercadoPago — Transbank requires an
  // actual browser form POST with a token_ws field (see WebpayCreateResult's backend comment).
  function submitWebpayForm(url: string, token: string) {
    const form = document.createElement('form')
    form.method = 'POST'
    form.action = url
    const input = document.createElement('input')
    input.type = 'hidden'
    input.name = 'token_ws'
    input.value = token
    form.appendChild(input)
    document.body.appendChild(form)
    form.submit()
  }

  return (
    <div className="mx-auto grid max-w-5xl gap-8 p-4 py-8 lg:grid-cols-3">
      <div className="lg:col-span-2">
        <h1 className="mb-6 text-2xl font-semibold text-dark">Checkout</h1>

        <div className="mb-6 flex items-center gap-4">
          {STEPS.map((label, index) => (
            <div key={label} className="flex items-center gap-2">
              <span
                className={`flex h-7 w-7 items-center justify-center rounded-full text-xs font-medium ${
                  index <= step ? 'bg-primary text-white' : 'bg-gray-200 text-secondary'
                }`}
              >
                {index + 1}
              </span>
              <span className={`text-sm ${index === step ? 'font-medium text-dark' : 'text-secondary'}`}>{label}</span>
            </div>
          ))}
        </div>

        {error && <p className="mb-4 rounded bg-red-50 p-3 text-sm text-red-700">{error}</p>}

        {step === 0 && (
          <div className="space-y-3">
            <input
              type="email"
              placeholder="Email"
              value={customer.email}
              onChange={(e) => setCustomer({ email: e.target.value })}
              className="w-full rounded border border-gray-400 px-3 py-2 text-sm"
            />
            <div className="grid grid-cols-2 gap-3">
              <input
                placeholder="Nombre"
                value={customer.firstName}
                onChange={(e) => setCustomer({ firstName: e.target.value })}
                className="rounded border border-gray-400 px-3 py-2 text-sm"
              />
              <input
                placeholder="Apellido"
                value={customer.lastName}
                onChange={(e) => setCustomer({ lastName: e.target.value })}
                className="rounded border border-gray-400 px-3 py-2 text-sm"
              />
            </div>
            <input
              placeholder="Teléfono"
              value={customer.phone}
              onChange={(e) => setCustomer({ phone: e.target.value })}
              className="w-full rounded border border-gray-400 px-3 py-2 text-sm"
            />
            <button
              type="button"
              disabled={!contactValid}
              onClick={() => setStep(1)}
              className="rounded bg-primary px-4 py-2 text-sm font-medium text-white disabled:opacity-40"
            >
              Continuar
            </button>
          </div>
        )}

        {step === 1 && (
          <div className="space-y-3">
            <input
              placeholder="Dirección"
              value={shippingAddress.addressLine1}
              onChange={(e) => setShippingAddress({ addressLine1: e.target.value })}
              className="w-full rounded border border-gray-400 px-3 py-2 text-sm"
            />
            <input
              placeholder="Depto / referencia (opcional)"
              value={shippingAddress.addressLine2 ?? ''}
              onChange={(e) => setShippingAddress({ addressLine2: e.target.value })}
              className="w-full rounded border border-gray-400 px-3 py-2 text-sm"
            />
            <div className="grid grid-cols-2 gap-3">
              <input
                placeholder="Ciudad"
                value={shippingAddress.city}
                onChange={(e) => setShippingAddress({ city: e.target.value })}
                className="rounded border border-gray-400 px-3 py-2 text-sm"
              />
              <input
                placeholder="Región"
                value={shippingAddress.state}
                onChange={(e) => setShippingAddress({ state: e.target.value })}
                className="rounded border border-gray-400 px-3 py-2 text-sm"
              />
            </div>
            <input
              placeholder="Código postal"
              value={shippingAddress.postalCode}
              onChange={(e) => setShippingAddress({ postalCode: e.target.value })}
              className="w-full rounded border border-gray-400 px-3 py-2 text-sm"
            />
            <div className="flex gap-2">
              <button type="button" onClick={() => setStep(0)} className="rounded border border-gray-400 px-4 py-2 text-sm">
                Atrás
              </button>
              <button
                type="button"
                disabled={!addressValid}
                onClick={() => setStep(2)}
                className="rounded bg-primary px-4 py-2 text-sm font-medium text-white disabled:opacity-40"
              >
                Continuar
              </button>
            </div>
          </div>
        )}

        {step === 2 && (
          <div className="space-y-4">
            <div>
              <h3 className="mb-2 text-sm font-medium text-dark">Método de pago</h3>
              <div className="flex gap-3">
                {config?.stripeEnabled && (
                  <label className="flex items-center gap-2 text-sm">
                    <input type="radio" checked={paymentMethod === 'stripe'} onChange={() => setPaymentMethod('stripe')} />
                    Tarjeta (Stripe)
                  </label>
                )}
                {config?.webpayEnabled && (
                  <label className="flex items-center gap-2 text-sm">
                    <input type="radio" checked={paymentMethod === 'webpay'} onChange={() => setPaymentMethod('webpay')} />
                    Webpay
                  </label>
                )}
                {config?.mercadopagoEnabled && (
                  <label className="flex items-center gap-2 text-sm">
                    <input
                      type="radio"
                      checked={paymentMethod === 'mercadopago'}
                      onChange={() => setPaymentMethod('mercadopago')}
                    />
                    MercadoPago
                  </label>
                )}
                <label className="flex items-center gap-2 text-sm">
                  <input type="radio" checked={paymentMethod === 'manual'} onChange={() => setPaymentMethod('manual')} />
                  Pago contra entrega
                </label>
              </div>
            </div>

            <textarea
              placeholder="Notas del pedido (opcional)"
              value={customerNotes}
              onChange={(e) => setCustomerNotes(e.target.value)}
              className="w-full rounded border border-gray-400 px-3 py-2 text-sm"
              rows={3}
            />

            <div className="flex gap-2">
              <button type="button" onClick={() => setStep(1)} className="rounded border border-gray-400 px-4 py-2 text-sm">
                Atrás
              </button>
              <button
                type="button"
                disabled={loading}
                onClick={handleConfirm}
                className="rounded bg-primary px-4 py-2 text-sm font-medium text-white disabled:opacity-40"
              >
                {loading ? 'Procesando...' : 'Confirmar Pedido'}
              </button>
            </div>
          </div>
        )}
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
        </div>
        <div className="mt-3 flex justify-between border-t border-gray-100 pt-3 font-semibold text-dark">
          <span>Total</span>
          <span>{formatCurrency(totals.total)}</span>
        </div>
      </div>
    </div>
  )
}
