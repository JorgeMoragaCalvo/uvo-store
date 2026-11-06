import { Link } from 'react-router-dom'

/**
 * El pago salió hacia la pasarela pero todavía no se confirmó. Lo muestran el retorno «pendiente» de MercadoPago
 * (Checkout) y la página de éxito cuando la pasarela no confirmó a tiempo (GAP-08). En los dos casos el comprador
 * no debe volver a pagar: el webhook o la conciliación confirman la orden y sale el correo.
 */
export default function PaymentInProcess({ orderNumber }: { orderNumber: string | null }) {
  return (
    <div className="mx-auto max-w-lg p-4 py-8 text-center sm:p-8">
      <h1 className="mb-2 text-xl font-semibold text-dark">Tu pago está en proceso</h1>
      <p role="status" className="mb-4 text-secondary">
        Cuando el medio de pago lo confirme, te avisaremos por correo. No hace falta que pagues de nuevo.
      </p>
      {orderNumber && (
        <p className="mb-6 text-secondary">
          Número de pedido: <strong>{orderNumber}</strong>
        </p>
      )}
      <div className="flex flex-wrap justify-center gap-3">
        <Link
          to={orderNumber ? `/track-order?order=${encodeURIComponent(orderNumber)}` : '/track-order'}
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
