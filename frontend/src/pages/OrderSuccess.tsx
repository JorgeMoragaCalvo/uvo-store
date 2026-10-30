import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import api from '../services/api'
import { useCartStore } from '../stores/useCartStore'
import { clearPendingPayment } from '../stores/useCheckoutStore'
import { useStoreTitle } from '../hooks/useDocumentTitle'

export default function OrderSuccess() {
  const [searchParams] = useSearchParams()
  const clearCart = useCartStore((state) => state.clearCart)

  const sessionId = searchParams.get('session_id')
  const orderParam = searchParams.get('order')

  const [status, setStatus] = useState<'loading' | 'ok' | 'error'>(sessionId ? 'loading' : 'ok')
  const [orderNumber, setOrderNumber] = useState<string | null>(orderParam)
  useStoreTitle(status === 'error' ? 'No pudimos confirmar tu pago' : status === 'ok' ? 'Pedido confirmado' : 'Verificando tu pago')

  useEffect(() => {
    if (!sessionId) return

    api.payment
      .verify(sessionId)
      .then((result) => {
        setOrderNumber(result.orderNumber)
        setStatus('ok')
        clearCart()
        clearPendingPayment()
      })
      .catch(() => setStatus('error'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId])

  // Webpay's server-side /webpay/return and MercadoPago's success backUrl both land here with
  // ?order=... directly (no client-side verification call needed, unlike Stripe's session_id) —
  // still need to clear the cart, which Stripe's branch above only does on its own path.
  useEffect(() => {
    if (!orderParam) return
    clearCart()
    // La orden guardada para reintentar el pago ya está pagada: no hay nada que retomar.
    clearPendingPayment()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orderParam])

  if (status === 'loading') {
    return (
      <p role="status" className="p-8 text-center text-secondary">
        Verificando tu pago...
      </p>
    )
  }

  if (status === 'error') {
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
