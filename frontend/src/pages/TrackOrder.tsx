import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import api from '../services/api'
import { formatCurrency } from '../utils/currency'
import type { OrderTracking } from '../types/api'

const STATUS_LABELS: Record<string, string> = {
  pending: 'Pendiente',
  processing: 'En preparación',
  shipped: 'Enviado',
  delivered: 'Entregado',
  cancelled: 'Cancelado',
}

const PAYMENT_STATUS_LABELS: Record<string, string> = {
  pending: 'Pendiente',
  paid: 'Pagado',
  failed: 'Fallido',
  refunded: 'Reembolsado',
}

export default function TrackOrder() {
  const [searchParams] = useSearchParams()
  const [orderNumber, setOrderNumber] = useState(searchParams.get('order') ?? '')
  const [result, setResult] = useState<OrderTracking | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    if (!orderNumber.trim()) return

    setLoading(true)
    setError(null)
    setResult(null)

    try {
      const data = await api.orders.track(orderNumber.trim())
      setResult(data)
    } catch {
      setError('No encontramos un pedido con ese número. Verifica e intenta nuevamente.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="mx-auto max-w-lg p-4 py-10">
      <h1 className="mb-2 text-2xl font-semibold text-dark">Rastrear Pedido</h1>
      <p className="mb-6 text-sm text-secondary">Ingresa tu número de pedido para ver su estado.</p>

      <form onSubmit={handleSubmit} className="mb-6 flex gap-2">
        <input
          value={orderNumber}
          onChange={(event) => setOrderNumber(event.target.value)}
          placeholder="ej: ORD-AAXFD5GP"
          className="flex-1 rounded border border-gray-400 px-3 py-2 text-sm"
        />
        <button
          type="submit"
          disabled={loading}
          className="rounded bg-primary px-4 py-2 text-sm font-medium text-white disabled:opacity-40"
        >
          {loading ? 'Buscando...' : 'Buscar'}
        </button>
      </form>

      {error && <p className="rounded bg-red-50 p-3 text-sm text-red-700">{error}</p>}

      {result && (
        <div className="rounded-lg border border-gray-400 p-5">
          <div className="mb-4 flex items-center justify-between">
            <span className="font-semibold text-dark">{result.orderNumber}</span>
            <span className="rounded-full bg-primary/10 px-3 py-1 text-xs font-medium text-primary">
              {STATUS_LABELS[result.status] ?? result.status}
            </span>
          </div>

          <div className="space-y-2 text-sm text-secondary">
            <div className="flex justify-between">
              <span>Pago</span>
              <span>{PAYMENT_STATUS_LABELS[result.paymentStatus] ?? result.paymentStatus}</span>
            </div>
            <div className="flex justify-between">
              <span>Total</span>
              <span className="font-medium text-dark">{formatCurrency(result.total)}</span>
            </div>
            <div className="flex justify-between">
              <span>Artículos</span>
              <span>{result.itemsCount}</span>
            </div>
            <div className="flex justify-between">
              <span>Fecha</span>
              <span>{new Date(result.createdAt).toLocaleDateString('es-CL')}</span>
            </div>
            {result.trackingNumber && (
              <div className="flex justify-between">
                <span>N° de seguimiento</span>
                <span>{result.trackingNumber}</span>
              </div>
            )}
            {result.trackingUrl && (
              <a href={result.trackingUrl} target="_blank" rel="noreferrer" className="block text-primary underline">
                Ver seguimiento del courier
              </a>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
