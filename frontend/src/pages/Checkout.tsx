import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { cartLineKey, useCartStore } from '../stores/useCartStore'
import { clearPendingPayment, loadPendingPayment, useCheckoutStore } from '../stores/useCheckoutStore'
import OrderSummary from '../components/cart/OrderSummary'
import api from '../services/api'
import { useStoreTitle } from '../hooks/useDocumentTitle'
import type { ShippingCoverage } from '../types/api'
import { addressSchema, contactSchema } from './checkoutSchemas'
import type { AddressValues, ContactValues } from './checkoutSchemas'

const STEPS = ['Contacto', 'Dirección', 'Pago'] as const

// 16 px en el celular: con menos, iOS acerca la pantalla al enfocar el campo.
const inputClass = 'w-full rounded border border-gray-400 px-3 py-2 text-base sm:text-sm'
const labelClass = 'mb-1 block text-sm text-secondary'

// PROD-24b. Cómo vuelve el comprador de una pasarela sin haber pagado: el backend lo manda aquí con uno de
// estos parámetros (WebpayController, PaymentServiceImpl, MercadoPagoServiceImpl). Antes no se leían y el
// comprador caía en el paso 1, con el formulario vacío y sin saber qué había pasado.
type ReturnNotice = 'canceled' | 'failed' | 'pending' | null

function returnNoticeFrom(params: URLSearchParams): ReturnNotice {
  if (params.get('pending')) return 'pending'
  if (params.get('error')) return 'failed'
  if (params.get('canceled')) return 'canceled'
  return null
}

const RETURN_MESSAGES: Record<'canceled' | 'failed', string> = {
  canceled: 'No se completó el pago. Tus datos siguen aquí: puedes intentarlo de nuevo o elegir otro medio de pago.',
  failed: 'El pago fue rechazado o no se pudo confirmar. Puedes intentarlo de nuevo o elegir otro medio de pago.',
}

// El mensaje de error va unido a su campo (aria-describedby), y el campo se marca inválido: un lector de
// pantalla lo anuncia al llegar al campo, y no solo a quien ve el texto rojo debajo.
function FieldError({ id, message }: { id: string; message?: string }) {
  if (!message) return null
  return (
    <p id={id} className="text-xs text-red-700">
      {message}
    </p>
  )
}

function fieldA11y(id: string, message?: string) {
  return {
    id,
    'aria-invalid': message ? true : undefined,
    'aria-describedby': message ? `${id}-error` : undefined,
  }
}

function Field({ id, label, error, children }: { id: string; label: string; error?: string; children: ReactNode }) {
  return (
    <div>
      <label htmlFor={id} className={labelClass}>
        {label}
      </label>
      {children}
      <FieldError id={`${id}-error`} message={error} />
    </div>
  )
}

function PaymentOption({ value, label }: { value: 'manual' | 'stripe' | 'webpay' | 'mercadopago'; label: string }) {
  const paymentMethod = useCheckoutStore((state) => state.paymentMethod)
  const setPaymentMethod = useCheckoutStore((state) => state.setPaymentMethod)
  return (
    <label className="flex items-center gap-2 text-sm">
      <input
        type="radio"
        name="paymentMethod"
        value={value}
        checked={paymentMethod === value}
        onChange={() => setPaymentMethod(value)}
      />
      {label}
    </label>
  )
}

export default function Checkout() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const items = useCartStore((state) => state.items)
  const totals = useCartStore((state) => state.totals)
  const region = useCartStore((state) => state.region)
  const commune = useCartStore((state) => state.commune)
  const couponCode = useCartStore((state) => state.couponCode)
  const lineErrors = useCartStore((state) => state.lineErrors)
  const totalsError = useCartStore((state) => state.totalsError)
  const setDestination = useCartStore((state) => state.setDestination)
  const setCouponCode = useCartStore((state) => state.setCouponCode)
  const validateCart = useCartStore((state) => state.validateCart)
  const clearCart = useCartStore((state) => state.clearCart)

  const { config, customer, shippingAddress, customerNotes, loading, error } = useCheckoutStore()
  const fetchConfig = useCheckoutStore((state) => state.fetchConfig)
  const setCustomer = useCheckoutStore((state) => state.setCustomer)
  const setShippingAddress = useCheckoutStore((state) => state.setShippingAddress)
  const setCustomerNotes = useCheckoutStore((state) => state.setCustomerNotes)
  const processCheckout = useCheckoutStore((state) => state.processCheckout)
  const resumePendingPayment = useCheckoutStore((state) => state.resumePendingPayment)

  const [step, setStep] = useState(0)
  const [coverage, setCoverage] = useState<ShippingCoverage[]>([])
  const [couponInput, setCouponInput] = useState('')
  const [couponChecked, setCouponChecked] = useState(false)
  const [selectedRegion, setSelectedRegion] = useState(region)
  const [returnNotice] = useState<ReturnNotice>(() => returnNoticeFrom(searchParams))
  // El número de la orden cuyo pago quedó en proceso; se lee una sola vez, antes de limpiar lo guardado.
  const [pendingOrderNumber] = useState(() => (returnNotice === 'pending' ? loadPendingPayment()?.orderNumber ?? null : null))
  const stepHeadingRef = useRef<HTMLHeadingElement>(null)
  const firstRender = useRef(true)
  useStoreTitle(returnNotice === 'pending' ? 'Pago en proceso' : `Checkout: ${STEPS[step]}`)

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

  // PROD-24b. El stock y la disponibilidad de lo que hay en el carrito, antes de que el comprador llegue a
  // «Confirmar»: el carrito vive en localStorage y puede tener días.
  useEffect(() => {
    validateCart()
  }, [validateCart])

  // Vuelta de la pasarela. Con el pago en proceso (MercadoPago) la orden ya existe y no se ofrece pagar
  // otra vez: se vacía el carrito, como en la página de éxito. Si se canceló o falló, se retoma el
  // checkout tal como estaba al salir, en el paso de pago.
  useEffect(() => {
    if (returnNotice === 'pending') {
      clearPendingPayment()
      clearCart()
      return
    }
    if (!returnNotice) return
    resumePendingPayment().then((pending) => {
      if (!pending) return
      contactForm.reset(pending.customer)
      addressForm.reset({
        addressLine1: pending.shippingAddress.addressLine1,
        addressLine2: pending.shippingAddress.addressLine2 ?? '',
        city: pending.shippingAddress.city,
        postalCode: pending.shippingAddress.postalCode,
        region: pending.region,
        commune: pending.commune,
      })
      setSelectedRegion(pending.region)
      setStep(2)
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [returnNotice])

  // «Atrás» del navegador desde la pasarela puede devolver esta página tal como quedó (bfcache), con el
  // botón en «Procesando…» para siempre.
  useEffect(() => {
    function onPageShow(event: PageTransitionEvent) {
      if (event.persisted) useCheckoutStore.setState({ loading: false })
    }
    window.addEventListener('pageshow', onPageShow)
    return () => window.removeEventListener('pageshow', onPageShow)
  }, [])

  // Al cambiar de paso, el foco va al título del paso nuevo: si no, quien navega con teclado o lector de
  // pantalla se queda en un botón que ya no existe y vuelve al principio de la página.
  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false
      return
    }
    stepHeadingRef.current?.focus()
  }, [step])

  // Tracked in local state rather than through react-hook-form's watch(): watch() makes the React
  // Compiler bail out of this whole component ("Use of incompatible library"), and all that's
  // needed here is re-rendering the commune list when the region changes.
  const regionField = addressForm.register('region')
  const communesForRegion = coverage.find((c) => c.region === selectedRegion)?.communes ?? []

  if (returnNotice === 'pending') {
    return (
      <div className="mx-auto max-w-lg p-4 py-8 text-center sm:p-8">
        <h1 className="mb-2 text-xl font-semibold text-dark">Tu pago está en proceso</h1>
        <p role="status" className="mb-4 text-secondary">
          Cuando el medio de pago lo confirme, te avisaremos por correo. No hace falta que pagues de nuevo.
        </p>
        {pendingOrderNumber && (
          <p className="mb-6 text-secondary">
            Número de pedido: <strong>{pendingOrderNumber}</strong>
          </p>
        )}
        <div className="flex flex-wrap justify-center gap-3">
          <Link
            to={pendingOrderNumber ? `/track-order?order=${encodeURIComponent(pendingOrderNumber)}` : '/track-order'}
            className="rounded bg-primary px-4 py-2 text-sm font-medium text-white"
          >
            Seguir mi pedido
          </Link>
          <Link to="/shop" className="rounded border border-gray-400 px-4 py-2 text-sm font-medium text-dark">
            Volver a la tienda
          </Link>
        </div>
      </div>
    )
  }

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

  const cartProblems = items
    .filter((item) => lineErrors[cartLineKey(item)])
    .map((item) => ({ key: cartLineKey(item), name: item.product.name, message: lineErrors[cartLineKey(item)] }))

  const contactErrors = contactForm.formState.errors
  const addressErrors = addressForm.formState.errors

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
    await setDestination(values.region, values.commune)
    setStep(2)
  }

  async function applyCoupon() {
    await setCouponCode(couponInput.trim())
    setCouponChecked(true)
  }

  async function handleConfirm() {
    // Un segundo clic mientras la primera confirmación sigue en vuelo crearía otra orden.
    if (useCheckoutStore.getState().loading) return
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

        <ol className="mb-6 flex flex-wrap items-center gap-4" aria-label="Pasos de la compra">
          {STEPS.map((label, index) => (
            <li key={label} className="flex items-center gap-2" aria-current={index === step ? 'step' : undefined}>
              <span
                aria-hidden="true"
                className={`flex h-7 w-7 items-center justify-center rounded-full text-xs font-medium ${
                  index <= step ? 'bg-primary text-white' : 'bg-gray-200 text-secondary'
                }`}
              >
                {index + 1}
              </span>
              <span className={`text-sm ${index === step ? 'font-medium text-dark' : 'text-secondary'}`}>{label}</span>
            </li>
          ))}
        </ol>

        {returnNotice && (
          <p role="alert" className="mb-4 rounded bg-amber-50 p-3 text-sm text-amber-800">
            {RETURN_MESSAGES[returnNotice]}
          </p>
        )}

        {error && (
          <p role="alert" className="mb-4 rounded bg-red-50 p-3 text-sm text-red-700">
            {error}
          </p>
        )}

        {cartProblems.length > 0 && (
          <div role="alert" className="mb-4 rounded bg-red-50 p-3 text-sm text-red-700">
            <p className="mb-1 font-medium">Hay productos de tu carrito que necesitan atención:</p>
            <ul className="list-inside list-disc">
              {cartProblems.map((problem) => (
                <li key={problem.key}>
                  {problem.name}: {problem.message}
                </li>
              ))}
            </ul>
            <Link to="/cart" className="mt-2 inline-block underline">
              Revisar el carrito
            </Link>
          </div>
        )}

        <h2 ref={stepHeadingRef} tabIndex={-1} className="mb-3 text-lg font-medium text-dark outline-none">
          {STEPS[step]}
        </h2>

        {step === 0 && (
          <form className="space-y-3" onSubmit={contactForm.handleSubmit(submitContact)} noValidate>
            <Field id="email" label="Email" error={contactErrors.email?.message}>
              <input
                type="email"
                autoComplete="email"
                placeholder="Email"
                className={inputClass}
                {...fieldA11y('email', contactErrors.email?.message)}
                {...contactForm.register('email')}
              />
            </Field>
            <div className="grid grid-cols-2 gap-3">
              <Field id="firstName" label="Nombre" error={contactErrors.firstName?.message}>
                <input
                  autoComplete="given-name"
                  placeholder="Nombre"
                  className={inputClass}
                  {...fieldA11y('firstName', contactErrors.firstName?.message)}
                  {...contactForm.register('firstName')}
                />
              </Field>
              <Field id="lastName" label="Apellido" error={contactErrors.lastName?.message}>
                <input
                  autoComplete="family-name"
                  placeholder="Apellido"
                  className={inputClass}
                  {...fieldA11y('lastName', contactErrors.lastName?.message)}
                  {...contactForm.register('lastName')}
                />
              </Field>
            </div>
            <Field id="phone" label="Teléfono" error={contactErrors.phone?.message}>
              <input
                type="tel"
                autoComplete="tel"
                placeholder="Teléfono"
                className={inputClass}
                {...fieldA11y('phone', contactErrors.phone?.message)}
                {...contactForm.register('phone')}
              />
            </Field>
            <button type="submit" className="rounded bg-primary px-4 py-2 text-sm font-medium text-white">
              Continuar
            </button>
          </form>
        )}

        {step === 1 && (
          <form className="space-y-3" onSubmit={addressForm.handleSubmit(submitAddress)} noValidate>
            <Field id="addressLine1" label="Dirección" error={addressErrors.addressLine1?.message}>
              <input
                autoComplete="address-line1"
                placeholder="Dirección"
                className={inputClass}
                {...fieldA11y('addressLine1', addressErrors.addressLine1?.message)}
                {...addressForm.register('addressLine1')}
              />
            </Field>
            <Field id="addressLine2" label="Depto / referencia (opcional)">
              <input
                id="addressLine2"
                autoComplete="address-line2"
                placeholder="Depto / referencia (opcional)"
                className={inputClass}
                {...addressForm.register('addressLine2')}
              />
            </Field>
            <div className="grid grid-cols-2 gap-3">
              <Field id="city" label="Ciudad" error={addressErrors.city?.message}>
                <input
                  autoComplete="address-level2"
                  placeholder="Ciudad"
                  className={inputClass}
                  {...fieldA11y('city', addressErrors.city?.message)}
                  {...addressForm.register('city')}
                />
              </Field>
              <Field id="postalCode" label="Código postal" error={addressErrors.postalCode?.message}>
                <input
                  autoComplete="postal-code"
                  placeholder="Código postal"
                  className={inputClass}
                  {...fieldA11y('postalCode', addressErrors.postalCode?.message)}
                  {...addressForm.register('postalCode')}
                />
              </Field>
            </div>

            {/* Picked from the store's real coverage rather than typed: the backend matches zones by
                exact string, so a free-text region essentially never matched. */}
            <Field id="region" label="Región" error={addressErrors.region?.message}>
              <select
                className={inputClass}
                {...fieldA11y('region', addressErrors.region?.message)}
                {...regionField}
                onChange={(e) => {
                  regionField.onChange(e)
                  setSelectedRegion(e.target.value)
                  // La comuna de la región anterior no es de esta: si se quedaba, se enviaba con la región nueva.
                  addressForm.setValue('commune', '')
                }}
              >
                <option value="">Elige una región</option>
                {coverage.map((c) => (
                  <option key={c.region} value={c.region}>{c.region}</option>
                ))}
              </select>
            </Field>

            {selectedRegion && (
              <Field id="commune" label="Comuna" error={addressErrors.commune?.message}>
                <select
                  className={inputClass}
                  {...fieldA11y('commune', addressErrors.commune?.message)}
                  {...addressForm.register('commune')}
                >
                  <option value="">Elige una comuna</option>
                  {communesForRegion.map((c) => (
                    <option key={c} value={c}>{c}</option>
                  ))}
                </select>
              </Field>
            )}

            {coverage.length === 0 && (
              <p role="status" className="rounded bg-amber-50 p-3 text-sm text-amber-800">
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
              <p role="alert" className="rounded bg-red-50 p-3 text-sm text-red-700">
                No hay envío disponible para la dirección seleccionada. Vuelve atrás y elige otra.
              </p>
            )}

            <fieldset>
              <legend className="mb-2 text-sm font-medium text-dark">Método de pago</legend>
              <div className="flex flex-wrap gap-x-4 gap-y-2">
                {config?.stripeEnabled && <PaymentOption value="stripe" label="Tarjeta (Stripe)" />}
                {config?.webpayEnabled && <PaymentOption value="webpay" label="Webpay" />}
                {config?.mercadopagoEnabled && <PaymentOption value="mercadopago" label="MercadoPago" />}
                <PaymentOption value="manual" label="Pago contra entrega" />
              </div>
            </fieldset>

            <Field id="customerNotes" label="Notas del pedido (opcional)">
              <textarea
                id="customerNotes"
                placeholder="Notas del pedido (opcional)"
                value={customerNotes}
                onChange={(e) => setCustomerNotes(e.target.value)}
                className={inputClass}
                rows={3}
              />
            </Field>

            <div className="flex gap-2">
              <button type="button" onClick={() => setStep(1)} className="rounded border border-gray-400 px-4 py-2 text-sm">
                Atrás
              </button>
              <button
                type="button"
                disabled={loading || shippingUnavailable || totalsError}
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
        <label htmlFor="couponCode" className="sr-only">
          Código de descuento
        </label>
        <div className="mb-3 flex gap-2">
          <input
            id="couponCode"
            placeholder="Código de descuento"
            value={couponInput}
            onChange={(e) => setCouponInput(e.target.value)}
            aria-describedby={couponChecked && couponCode ? 'couponCode-result' : undefined}
            className="min-w-0 flex-1 rounded border border-gray-400 px-3 py-2 text-base sm:text-sm"
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
        {/* Región viva siempre montada: si apareciera junto con el texto, el lector de pantalla no lo
            anunciaría. Y sin totales nuevos no se puede decir que el código no vale. */}
        <div role="status" id="couponCode-result">
          {couponChecked && couponCode && !totalsError && (
            totals.couponApplied
              ? <p className="mb-3 text-xs text-green-700">Cupón aplicado</p>
              : <p className="mb-3 text-xs text-red-700">El código no es válido</p>
          )}
        </div>

        {totalsError && (
          <p role="alert" className="mb-3 rounded bg-red-50 p-2 text-xs text-red-700">
            No pudimos actualizar los totales. Revisa tu conexión o tu carrito e inténtalo de nuevo.
          </p>
        )}

        <OrderSummary />
      </div>
    </div>
  )
}
