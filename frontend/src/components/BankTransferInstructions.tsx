import type { BankTransfer } from '../types/api'
import { formatCurrency } from '../utils/currency'

interface Props {
  transfer: BankTransfer
  /** Con la orden ya creada: el monto exacto, la referencia y la fecha límite. */
  order?: { orderNumber: string; total: number; createdAt: string }
}

/** La fecha límite como la lee el comprador: «20 de noviembre a las 15:30». */
function formatDeadline(createdAt: string, hours: number): string {
  const deadline = new Date(new Date(createdAt).getTime() + hours * 3_600_000)
  const day = deadline.toLocaleDateString('es-CL', { day: 'numeric', month: 'long' })
  const time = deadline.toLocaleTimeString('es-CL', { hour: '2-digit', minute: '2-digit' })
  return `${day} a las ${time}`
}

/**
 * IMP-07. Cómo pagar por transferencia: los datos de la tienda y el plazo. En el checkout, antes de confirmar; en la
 * confirmación, con el monto, la referencia y la fecha límite, que es lo que el comprador necesita para pagar.
 */
export default function BankTransferInstructions({ transfer, order }: Props) {
  const rows: Array<[string, string | null]> = [
    ['Titular', transfer.holderName],
    ['RUT', transfer.holderRut],
    ['Banco', transfer.bankName],
    [transfer.accountTypeLabel ?? 'Cuenta', transfer.accountNumber],
  ]
  if (order) {
    rows.push(['Monto', formatCurrency(order.total)], ['Referencia', order.orderNumber])
  }

  return (
    <section aria-labelledby="bank-transfer-title" className="rounded-lg border border-gray-200 bg-gray-50 p-4 text-left text-sm">
      <h2 id="bank-transfer-title" className="mb-2 font-medium text-dark">
        Datos para la transferencia
      </h2>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
        {rows.map(([label, value]) => (
          <div key={label} className="contents">
            <dt className="text-secondary">{label}</dt>
            <dd className="break-all font-medium text-dark">{value}</dd>
          </div>
        ))}
      </dl>
      <p className="mt-3 text-secondary">
        Envía el comprobante a <strong className="text-dark">{transfer.noticeEmail}</strong>.
        {transfer.instructions && <> {transfer.instructions}</>}
      </p>
      <p className="mt-2 text-secondary">
        {order ? (
          <>
            Tienes hasta el <strong className="text-dark">{formatDeadline(order.createdAt, transfer.paymentWindowHours)}</strong>{' '}
            para pagar; después el pedido se cancela.
          </>
        ) : (
          <>
            Tendrás {transfer.paymentWindowHours} horas para pagar desde que confirmes el pedido; después se cancela. El
            pedido se despacha cuando la tienda confirme la transferencia.
          </>
        )}
      </p>
    </section>
  )
}
