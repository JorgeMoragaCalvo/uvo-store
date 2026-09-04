import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useCartStore } from '../stores/useCartStore'
import { useCheckoutStore } from '../stores/useCheckoutStore'
import { formatCurrency } from '../utils/currency'
import api from '../services/api'
import type { ShippingCoverage } from '../types/api'
import { addressSchema, contactSchema } from './checkoutSchemas'
import type { AddressValues, ContactValues } from './checkoutSchemas'

const STEPS = ['Contacto', 'Dirección', 'Pago'] as const

const inputClass = 'w-full rounded border border-gray-400 px-3 py-2 text-sm'

function FieldError({ message }: { message?: string }) {
  if (!message) return null
  return <p className="text-xs text-red-700">{message}</p>
}

export default function Checkout() {
  const navigate = useNavigate()
  const items = useCartStore((state) => state.items)
  const totals = useCartStore((state) => state.totals)
  const region = useCartStore((state) => state.region)
  const commune = useCartStore((state) => state.commune)
  const couponCode = useCartStore((state) => state.couponCode)
  const setDestination = useCartStore((state) => state.setDestination)
  const setCouponCode = useCartStore((state) => state.setCouponCode)

  const { config, customer, shippingAddress, paymentMethod, customerNotes, loading, error } = useCheckoutStore()
  const fetchConfig = useCheckoutStore((state) => state.fetchConfig)
  const setCustomer = useCheckoutStore((state) => state.setCustomer)
  const setShippingAddress = useCheckoutStore((state) => state.setShippingAddress)
  const setPaymentMethod = useCheckoutStore((state) => state.setPaymentMethod)
  const setCustomerNotes = useCheckoutStore((state) => state.setCustomerNotes)
  const processCheckout = useCheckoutStore((state) => state.processCheckout)

  const [step, setStep] = useState(0)
  const [coverage, setCoverage] = useState<ShippingCoverage[]>([])
  const [couponInput, setCouponInput] = useState('')
  const [couponChecked, setCouponChecked] = useState(false)
  const [selectedRegion, setSelectedRegion] = useState(region)

  const contactForm = useForm<ContactValues>({
    resolver: zodResolver(contactSchema),
    mode: 'onTouched',
    defaultValues: customer,
  })

  const addressForm = useForm<AddressValues>({
    resolver: zodResolver(addressSchema),
    mode: 'onTouched',
    defaultValues: {
      addressLine1: shippingAddress.addressLine1,
      addressLine2: shippingAddress.addressLine2 ?? '',
      city: shippingAddress.city,
      postalCode: shippingAddress.postalCode,
      region,
      commune,
    },
  })

  useEffect(() => {
    fetchConfig()
  }, [fetchConfig])

  useEffect(() => {
    api.shipping
      .coverage()
      .then(setCoverage)
      .catch(() => setCoverage([]))
  }, [])

  // Tracked in local state rather than through react-hook-form's watch(): watch() makes the React
  // Compiler bail out of this whole component ("Use of incompatible library"), and all that's
  // needed here is re-rendering the commune list when the region changes.
  const regionField = addressForm.register('region')
  const communesForRegion = coverage.find((c) => c.region === selectedRegion)?.communes ?? []

  if (items.length === 0) {
    return (
      <div className="mx-auto max-w-lg p-8 text-center">
        <h1 className="mb-2 text-xl font-semibold text-dark">Tu carrito está vacío</h1>
        <Link to="/shop" className="text-primary underline">Ir a la tienda</Link>
      </div>
    )
  }

  // A7: the store ships but doesn't reach the chosen address. Distinct from free shipping, which is
  // how it used to look — and used to be priced.
  const shippingUnavailable = totals.shippingEnabled && !totals.shippingAvailable

  function submitContact(values: ContactValues) {
    setCustomer(values)
    setStep(1)
  }

  async function submitAddress(values: AddressValues) {
    setShippingAddress({
      addressLine1: values.addressLine1,
      addressLine2: values.addressLine2 ?? '',
      city: values.city,
      // The order's address keeps the region in `state`, which is what it has always meant; the
      // difference now is that the same value also travels as `region` for zone matching.
      state: values.region,
      postalCode: values.postalCode,
    })
    await setDestination(values.region, values.commune ?? '')
    setStep(2)
  }

  async function applyCoupon() {
    await setCouponCode(couponInput.trim())
    setCouponChecked(true)
  }

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
          <form className="space-y-3" onSubmit={contactForm.handleSubmit(submitContact)} noValidate>
            <div>
              <input type="email" placeholder="Email" className={inputClass} {...contactForm.register('email')} />
              <FieldError message={contactForm.formState.errors.email?.message} />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <input placeholder="Nombre" className={inputClass} {...contactForm.register('firstName')} />
                <FieldError message={contactForm.formState.errors.firstName?.message} />
              </div>
              <div>
                <input placeholder="Apellido" className={inputClass} {...contactForm.register('lastName')} />
                <FieldError message={contactForm.formState.errors.lastName?.message} />
              </div>
            </div>
            <div>
              <input placeholder="Teléfono" className={inputClass} {...contactForm.register('phone')} />
              <FieldError message={contactForm.formState.errors.phone?.message} />
            </div>
            <button type="submit" className="rounded bg-primary px-4 py-2 text-sm font-medium text-white">
              Continuar
            </button>
          </form>
        )}

        {step === 1 && (
          <form className="space-y-3" onSubmit={addressForm.handleSubmit(submitAddress)} noValidate>
            <div>
              <input placeholder="Dirección" className={inputClass} {...addressForm.register('addressLine1')} />
              <FieldError message={addressForm.formState.errors.addressLine1?.message} />
            </div>
            <input
              placeholder="Depto / referencia (opcional)"
              className={inputClass}
              {...addressForm.register('addressLine2')}
            />
            <div className="grid grid-cols-2 gap-3">
              <div>
                <input placeholder="Ciudad" className={inputClass} {...addressForm.register('city')} />
                <FieldError message={addressForm.formState.errors.city?.message} />
              </div>
              <div>
                <input placeholder="Código postal" className={inputClass} {...addressForm.register('postalCode')} />
                <FieldError message={addressForm.formState.errors.postalCode?.message} />
              </div>
            </div>

            {/* Picked from the store's real coverage rather than typed: the backend matches zones by
                exact string, so a free-text region essentially never matched. */}
            <div>
              <label className="mb-1 block text-sm text-secondary" htmlFor="region">Región</label>
              <select
                id="region"
                className={inputClass}
                {...regionField}
                onChange={(e) => {
                  regionField.onChange(e)
                  setSelectedRegion(e.target.value)
                }}
              >
                <option value="">Elige una región</option>
                {coverage.map((c) => (
                  <option key={c.region} value={c.region}>{c.region}</option>
                ))}
              </select>
              <FieldError message={addressForm.formState.errors.region?.message} />
            </div>

            {communesForRegion.length > 0 && (
              <div>
                <label className="mb-1 block text-sm text-secondary" htmlFor="commune">Comuna</label>
                <select id="commune" className={inputClass} {...addressForm.register('commune')}>
                  <option value="">Elige una comuna</option>
                  {communesForRegion.map((c) => (
                    <option key={c} value={c}>{c}</option>
                  ))}
                </select>
              </div>
            )}

            {coverage.length === 0 && (
              <p className="rounded bg-amber-50 p-3 text-sm text-amber-800">
                Esta tienda todavía no tiene zonas de envío configuradas.
              </p>
            )}

            <div className="flex gap-2">
              <button type="button" onClick={() => setStep(0)} className="rounded border border-gray-400 px-4 py-2 text-sm">
                Atrás
              </button>
              <button type="submit" className="rounded bg-primary px-4 py-2 text-sm font-medium text-white">
                Continuar
              </button>
            </div>
          </form>
        )}

        {step === 2 && (
          <div className="space-y-4">
            {shippingUnavailable && (
              <p className="rounded bg-red-50 p-3 text-sm text-red-700">
                No hay envío disponible para la dirección seleccionada. Vuelve atrás y elige otra.
              </p>
            )}

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
              className={inputClass}
              rows={3}
            />

            <div className="flex gap-2">
              <button type="button" onClick={() => setStep(1)} className="rounded border border-gray-400 px-4 py-2 text-sm">
                Atrás
              </button>
              <button
                type="button"
                disabled={loading || shippingUnavailable}
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

        {/* The discount line in Cart.tsx could never populate: nothing ever sent a coupon code
            because there was nowhere to enter one. This is that missing input. */}
        <div className="mb-3 flex gap-2">
          <input
            placeholder="Código de descuento"
            value={couponInput}
            onChange={(e) => setCouponInput(e.target.value)}
            className="min-w-0 flex-1 rounded border border-gray-400 px-3 py-2 text-sm"
          />
          <button
            type="button"
            onClick={applyCoupon}
            disabled={!couponInput.trim()}
            className="rounded border border-gray-400 px-3 py-2 text-sm disabled:opacity-40"
          >
            Aplicar
          </button>
        </div>
        {couponChecked && couponCode && (
          totals.couponApplied
            ? <p className="mb-3 text-xs text-green-700">Cupón aplicado</p>
            : <p className="mb-3 text-xs text-red-700">El código no es válido</p>
        )}

        <div className="flex flex-col gap-2 text-sm text-secondary">
          <div className="flex justify-between">
            <span>Subtotal</span>
            <span>{formatCurrency(totals.subtotal)}</span>
          </div>
          <div className="flex justify-between">
            <span>Envío</span>
            <span>
              {shippingUnavailable
                ? 'No disponible'
                : totals.shippingCost > 0
                  ? formatCurrency(totals.shippingCost)
                  : 'Gratis'}
            </span>
          </div>
          {totals.discountAmount > 0 && (
            <div className="flex justify-between text-green-700">
              <span>Descuento</span>
              <span>-{formatCurrency(totals.discountAmount)}</span>
            </div>
          )}
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
