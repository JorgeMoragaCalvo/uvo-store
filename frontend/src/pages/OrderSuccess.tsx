import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import api from '../services/api'
import { useCartStore } from '../stores/useCartStore'
import { clearPendingPayment } from '../stores/useCheckoutStore'
import { useStoreTitle } from '../hooks/useDocumentTitle'
import PaymentInProcess from '../components/PaymentInProcess'

/** GAP-08. Cuánto espera la página de éxito de MercadoPago a que el webhook confirme el pago: unos 20 s. */
const CONFIRM_POLL_MS = 2000
const CONFIRM_POLL_ATTEMPTS = 10

type View = 'verifying' | 'confirming' | 'ok' | 'pending' | 'error'

const TITLES: Record<View, string> = {
  verifying: 'Verificando tu pago',
  confirming: 'Confirmando tu pago',
  ok: 'Pedido confirmado',
  pending: 'Pago en proceso',
  error: 'No pudimos confirmar tu pago',
}

export default function OrderSuccess() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const clearCart = useCartStore((state) => state.clearCart)

  const sessionId = searchParams.get('session_id')
  const orderParam = searchParams.get('order')
  // GAP-08. MercadoPago vuelve aquí con el pago aprobado en su página, pero lo confirma el webhook, que puede
  // llegar después. La marca la pone su back_url; sin ella (pago manual, Webpay ya confirmado) no hay que esperar.
  const confirmOrder = searchParams.get('gateway') === 'mercadopago' ? orderParam : null

  const [view, setView] = useState<View>(sessionId ? 'verifying' : confirmOrder ? 'confirming' : 'ok')
  const [orderNumber, setOrderNumber] = useState<string | null>(orderParam)
  useStoreTitle(TITLES[view])

  // El carrito y la orden guardada se vacían solo cuando ya no hay nada que reintentar: pago confirmado, o en
  // camino. Con un pago rechazado se vuelve al checkout con los dos intactos.
  function settle(next: 'ok' | 'pending') {
    clearCart()
    clearPendingPayment()
    setView(next)
  }

  useEffect(() => {
    if (!sessionId) return

    api.payment
      .verify(sessionId)
      .then((result) => {
        setOrderNumber(result.orderNumber)
        // GAP-08. Antes cualquier respuesta daba las gracias, también una sesión que volvió sin pagar.
        if (result.orderPaymentStatus === 'PAID') settle('ok')
        else if (result.orderPaymentStatus === 'FAILED') navigate('/checkout?error=stripe', { replace: true })
        else settle('pending')
      })
      .catch(() => setView('error'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId])

  useEffect(() => {
    if (!confirmOrder) return
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined

    async function poll(attempt: number) {
      // Un fallo de red cuenta como «todavía no»: al final se muestra «en proceso», que es cierto mientras nadie
      // haya dicho lo contrario.
      // El seguimiento público trae los estados en minúscula; verify-payment, en mayúscula.
      const status = await api.orders.track(confirmOrder as string).then(
        (tracking) => tracking.paymentStatus.toUpperCase(),
        () => null,
      )
      if (cancelled) return
      if (status === 'PAID') settle('ok')
      else if (status === 'FAILED') navigate('/checkout?error=mercadopago', { replace: true })
      else if (attempt + 1 >= CONFIRM_POLL_ATTEMPTS) settle('pending')
      else timer = setTimeout(() => poll(attempt + 1), CONFIRM_POLL_MS)
    }
    poll(0)

    return () => {
      cancelled = true
      clearTimeout(timer)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [confirmOrder])

  // El pago manual y Webpay (que solo llega aquí pagado) no tienen nada que confirmar.
  useEffect(() => {
    if (!orderParam || confirmOrder) return
    clearCart()
    // La orden guardada para reintentar el pago ya está pagada: no hay nada que retomar.
    clearPendingPayment()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orderParam])

  if (view === 'verifying' || view === 'confirming') {
    return (
      <p role="status" className="p-8 text-center text-secondary">
        {view === 'verifying' ? 'Verificando tu pago...' : 'Confirmando tu pago...'}
      </p>
    )
  }

  if (view === 'pending') {
    return <PaymentInProcess orderNumber={orderNumber} />
  }

  if (view === 'error') {
    return (
      <div className="mx-auto max-w-lg p-4 py-8 text-center sm:p-8">
        <h1 className="mb-2 text-xl font-semibold text-dark">No pudimos confirmar tu pago</h1>
        {/* PROD-24b. El texto pedía «tu número de sesión de pago», pero no lo mostraba en ninguna parte. */}
        <p role="alert" className="mb-2 text-secondary">
          Puede que el pago se esté procesando todavía. Si el cargo se realizó, contáctanos con este número de
          sesión de pago:
        </p>
        <p className="mb-6 break-all font-mono text-sm text-dark">{sessionId}</p>
        <div className="flex flex-wrap justify-center gap-3">
          <Link to="/track-order" className="rounded border border-gray-400 px-4 py-2 text-sm font-medium text-dark">
            Seguir mi pedido
          </Link>
          <Link to="/" className="rounded bg-primary px-4 py-2 text-sm font-medium text-white">
            Volver al inicio
          </Link>
        </div>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-lg p-4 py-8 text-center sm:p-8">
      <div aria-hidden="true" className="mb-4 text-5xl">✓</div>
      <h1 className="mb-2 text-2xl font-semibold text-dark">¡Gracias por tu compra!</h1>
      {orderNumber && <p className="mb-4 text-secondary">Número de pedido: <strong>{orderNumber}</strong></p>}

      <div className="mb-6 rounded-lg bg-gray-50 p-4 text-left text-sm text-secondary">
        <p className="mb-1 font-medium text-dark">¿Qué sigue?</p>
        <ul className="list-inside list-disc space-y-1">
          <li>Te enviaremos un correo con la confirmación de tu pedido.</li>
          <li>Puedes revisar el estado de tu pedido en cualquier momento.</li>
        </ul>
      </div>

      <div className="flex flex-wrap justify-center gap-3">
        {orderNumber && (
          <Link
            to={`/track-order?order=${encodeURIComponent(orderNumber)}`}
            className="rounded border border-gray-400 px-4 py-2 text-sm font-medium text-dark"
          >
            Seguir mi pedido
          </Link>
        )}
        <Link to="/" className="rounded border border-gray-400 px-4 py-2 text-sm font-medium text-dark">Volver al inicio</Link>
        <Link to="/shop" className="rounded bg-primary px-4 py-2 text-sm font-medium text-white">Seguir comprando</Link>
      </div>
    </div>
  )
}
