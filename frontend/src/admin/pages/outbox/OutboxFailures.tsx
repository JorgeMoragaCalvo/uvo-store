import { Fragment, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import DataTablePagination from '@/admin/components/DataTablePagination'
import { hasPermission, useAdminAuthStore } from '@/admin/stores/useAdminAuthStore'
import { useAdminOutboxStore } from '@/admin/stores/useAdminOutboxStore'
import type { OutboxFailure, OutboxType } from '@/admin/types/admin'

const TYPE_LABEL: Record<OutboxType, string> = {
  POS_ORDER: 'Venta al POS',
  ORDER_PLACED_EMAIL: 'Correo de pedido recibido',
  ORDER_CONFIRMATION_EMAIL: 'Correo de confirmación de pago',
  CUSTOMER_INVITATION_EMAIL: 'Correo de invitación a crear cuenta',
}

/**
 * GAP-04. Las entregas del outbox que fallaron de forma definitiva —la venta al POS, los correos de una orden— y que
 * antes solo se veían en Sentry y se resolvían con SQL. Reintentar las devuelve a la cola; marcar resuelta las cierra
 * sin reenviar, cuando ya se resolvieron por otra vía.
 */
export default function OutboxFailures() {
  const headingRef = useRef<HTMLHeadingElement>(null)
  const data = useAdminOutboxStore((state) => state.data)
  const loading = useAdminOutboxStore((state) => state.loading)
  const fetchFailed = useAdminOutboxStore((state) => state.fetchFailed)
  const user = useAdminAuthStore((state) => state.user)
  const canManage = hasPermission(user, 'orders.manage')

  const [page, setPage] = useState(1)
  const [openId, setOpenId] = useState<number | null>(null)

  useEffect(() => {
    fetchFailed(page).catch(() => toast.error('No se pudieron cargar los envíos fallidos'))
  }, [fetchFailed, page])

  function handleDone() {
    setOpenId(null)
    // PROD-24b. La fila ya no existe: el foco va al título en vez de caer al principio de la página.
    headingRef.current?.focus()
  }

  const columns = canManage ? 6 : 5

  return (
    <div className="flex flex-col gap-4">
      <h1 ref={headingRef} tabIndex={-1} className="text-2xl font-semibold outline-none">Envíos fallidos</h1>
      <p className="text-sm text-muted-foreground">
        Ventas al POS y correos de las órdenes que no se pudieron entregar y necesitan que alguien los revise.
      </p>

      <div className="rounded-lg border bg-background">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Envío</TableHead>
              <TableHead>Orden</TableHead>
              <TableHead>Error</TableHead>
              <TableHead>Intentos</TableHead>
              <TableHead>Último intento</TableHead>
              {canManage && <TableHead className="text-right">Acciones</TableHead>}
            </TableRow>
          </TableHeader>
          <TableBody>
            {loading && (
              <TableRow>
                <TableCell colSpan={columns} className="py-8 text-center text-muted-foreground">
                  Cargando…
                </TableCell>
              </TableRow>
            )}
            {!loading && data?.content.length === 0 && (
              <TableRow>
                <TableCell colSpan={columns} className="py-8 text-center text-muted-foreground">
                  No hay envíos fallidos.
                </TableCell>
              </TableRow>
            )}
            {!loading &&
              data?.content.map((failure) => (
                <Fragment key={failure.id}>
                  <TableRow>
                    <TableCell>
                      <div className="font-medium">{TYPE_LABEL[failure.type] ?? failure.type}</div>
                      <div className="mt-1 flex flex-wrap gap-1">
                        {failure.uncertain && <Badge variant="destructive">Pudo haber llegado</Badge>}
                        {failure.legacy && <Badge variant="secondary">Anterior al outbox</Badge>}
                      </div>
                    </TableCell>
                    <TableCell>
                      <Link to={`/admin/orders/${failure.orderId}`} className="font-medium hover:underline">
                        {failure.orderNumber}
                      </Link>
                    </TableCell>
                    <TableCell className="max-w-xs text-sm break-words whitespace-normal text-muted-foreground">
                      {failure.lastError ?? '—'}
                    </TableCell>
                    <TableCell>{failure.attempts}</TableCell>
                    <TableCell className="text-muted-foreground">
                      {failure.updatedAt ? new Date(failure.updatedAt).toLocaleString('es-CL') : '—'}
                    </TableCell>
                    {canManage && (
                      <TableCell className="text-right">
                        <Button
                          size="sm"
                          variant="outline"
                          aria-expanded={openId === failure.id}
                          onClick={() => setOpenId(openId === failure.id ? null : failure.id)}
                        >
                          {openId === failure.id ? 'Cerrar' : 'Revisar'}
                        </Button>
                      </TableCell>
                    )}
                  </TableRow>
                  {canManage && openId === failure.id && (
                    <TableRow>
                      <TableCell colSpan={columns}>
                        <FailureResolver failure={failure} onDone={handleDone} />
                      </TableCell>
                    </TableRow>
                  )}
                </Fragment>
              ))}
          </TableBody>
        </Table>
      </div>

      {data && (
        <DataTablePagination page={page} totalPages={data.totalPages} totalElements={data.totalElements} onPageChange={setPage} />
      )}
    </div>
  )
}

/**
 * Las dos salidas de una entrega fallida. Las dos piden nota: el sistema no puede comprobar lo que el operador revisó,
 * y la nota queda en el historial de la orden. Una venta incierta pide además confirmar que se revisó el POS, porque
 * reenviarla si ya está allí puede emitir un segundo documento tributario.
 */
function FailureResolver({ failure, onDone }: { failure: OutboxFailure; onDone: () => void }) {
  const retry = useAdminOutboxStore((state) => state.retry)
  const resolve = useAdminOutboxStore((state) => state.resolve)
  const [note, setNote] = useState('')
  const [posChecked, setPosChecked] = useState(false)
  const [busy, setBusy] = useState(false)
  const noteMissing = note.trim() === ''

  async function run(action: () => Promise<void>, successMessage: string) {
    setBusy(true)
    try {
      await action()
      toast.success(successMessage)
      onDone()
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudo completar la acción')
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-col gap-2 rounded-md border border-dashed p-3">
      {failure.legacy ? (
        <p className="text-sm text-muted-foreground">
          Esta venta es anterior al outbox y no indica a qué empresa del POS va, así que no se puede reintentar.
          Revísala en el POS y márcala resuelta.
        </p>
      ) : failure.uncertain ? (
        <p className="text-sm text-amber-800">
          No se sabe si esta venta llegó al POS. Revísala en el POS antes de reintentar: si ya está, reenviarla puede
          emitir un segundo documento tributario. Si ya está, márcala resuelta.
        </p>
      ) : (
        <p className="text-sm text-muted-foreground">
          Reintentar la devuelve a la cola y sale en la próxima pasada del envío. Marcarla resuelta la cierra sin
          reenviar.
        </p>
      )}
      <Label htmlFor={`outbox-${failure.id}-note`}>Nota</Label>
      <Input
        id={`outbox-${failure.id}-note`}
        value={note}
        onChange={(event) => setNote(event.target.value)}
        placeholder="Qué se revisó o cómo se resolvió"
      />
      {failure.uncertain && !failure.legacy && (
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            className="size-4"
            checked={posChecked}
            onChange={(event) => setPosChecked(event.target.checked)}
          />
          Revisé en el POS que la venta no está
        </label>
      )}
      <div className="flex flex-wrap gap-2">
        {!failure.legacy && (
          <Button
            size="sm"
            disabled={busy || noteMissing || (failure.uncertain && !posChecked)}
            onClick={() => run(() => retry(failure.id, note, posChecked), 'Envío devuelto a la cola')}
          >
            Reintentar
          </Button>
        )}
        <Button
          size="sm"
          variant="outline"
          disabled={busy || noteMissing}
          onClick={() => run(() => resolve(failure.id, note), 'Envío marcado como resuelto')}
        >
          Marcar resuelta
        </Button>
      </div>
    </div>
  )
}
