import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import api from '../services/api'
import { useCartStore } from '../stores/useCartStore'

export default function OrderSuccess() {
  const [searchParams] = useSearchParams()
  const clearCart = useCartStore((state) => state.clearCart)

  const sessionId = searchParams.get('session_id')
  const orderParam = searchParams.get('order')

  const [status, setStatus] = useState<'loading' | 'ok' | 'error'>(sessionId ? 'loading' : 'ok')
  const [orderNumber, setOrderNumber] = useState<string | null>(orderParam)

  useEffect(() => {
    if (!sessionId) return

    api.payment
      .verify(sessionId)
      .then((result) => {
        setOrderNumber(result.orderNumber)
        setStatus('ok')
        clearCart()
      })
      .catch(() => setStatus('error'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId])

  if (status === 'loading') {
    return <p className="p-8 text-center text-secondary">Verificando tu pago...</p>
  }

  if (status === 'error') {
    return (
      <div className="mx-auto max-w-lg p-8 text-center">
        <h1 className="mb-2 text-xl font-semibold text-dark">No pudimos confirmar tu pago</h1>
        <p className="mb-6 text-secondary">Si el cargo se realizó, contáctanos con tu número de sesión de pago.</p>
        <Link to="/" className="text-primary underline">Volver al inicio</Link>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-lg p-8 text-center">
      <div className="mb-4 text-5xl">✓</div>
      <h1 className="mb-2 text-2xl font-semibold text-dark">¡Gracias por tu compra!</h1>
      {orderNumber && <p className="mb-4 text-secondary">Número de pedido: <strong>{orderNumber}</strong></p>}

      <div className="mb-6 rounded-lg bg-gray-50 p-4 text-left text-sm text-secondary">
        <p className="mb-1 font-medium text-dark">¿Qué sigue?</p>
        <ul className="list-inside list-disc space-y-1">
          <li>Te enviaremos un correo con la confirmación de tu pedido.</li>
          <li>Puedes revisar el estado de tu pedido en cualquier momento.</li>
        </ul>
      </div>

      <div className="flex justify-center gap-3">
        <Link to="/" className="rounded border border-gray-200 px-4 py-2 text-sm font-medium text-dark">Volver al inicio</Link>
        <Link to="/shop" className="rounded bg-primary px-4 py-2 text-sm font-medium text-white">Seguir comprando</Link>
      </div>
    </div>
  )
}
