import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { ArrowLeft, Star, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog'
import DataTablePagination from '@/admin/components/DataTablePagination'
import adminApi from '@/admin/services/adminApi'
import { useAdminCustomersStore } from '@/admin/stores/useAdminCustomersStore'
import type { AdminCustomerDetailDto, ShippingAddressDto } from '@/admin/types/admin'

const STATUS_LABEL: Record<string, string> = {
  GUEST: 'Invitado',
  INVITED: 'Invitado con cuenta',
  ACTIVE: 'Activo',
}

function formatCurrency(value: number) {
  return new Intl.NumberFormat('es-CL', { style: 'currency', currency: 'CLP' }).format(value)
}

export default function CustomerDetail() {
  const { id } = useParams()
  const navigate = useNavigate()
  const customer = useAdminCustomersStore((state) => state.detail)
  const loading = useAdminCustomersStore((state) => state.detailLoading)
  const fetchDetail = useAdminCustomersStore((state) => state.fetchDetail)

  useEffect(() => {
    if (!id) return
    fetchDetail(Number(id)).catch(() => toast.error('No se pudo cargar el cliente'))
  }, [id, fetchDetail])

  if (loading || !customer) {
    return <p className="text-muted-foreground">Cargando…</p>
  }

  return <CustomerDetailContent key={customer.id} customer={customer} onBack={() => navigate('/admin/customers')} />
}

function CustomerDetailContent({ customer, onBack }: { customer: AdminCustomerDetailDto; onBack: () => void }) {
  const orders = useAdminCustomersStore((state) => state.orders)
  const ordersLoading = useAdminCustomersStore((state) => state.ordersLoading)
  const fetchOrders = useAdminCustomersStore((state) => state.fetchOrders)
  const refreshDetail = useAdminCustomersStore((state) => state.refreshDetail)

  const [ordersPage, setOrdersPage] = useState(1)
  const [busyAddressId, setBusyAddressId] = useState<number | null>(null)

  useEffect(() => {
    fetchOrders(customer.id, ordersPage).catch(() => toast.error('No se pudo cargar el historial de compras'))
  }, [customer.id, ordersPage, fetchOrders])

  async function handleSetDefault(address: ShippingAddressDto) {
    setBusyAddressId(address.id)
    try {
      await adminApi.customers.setDefaultAddress(customer.id, address.id)
      await refreshDetail()
      toast.success('Dirección marcada como predeterminada')
    } catch {
      toast.error('No se pudo actualizar la dirección')
    } finally {
      setBusyAddressId(null)
    }
  }

  async function handleDeleteAddress(address: ShippingAddressDto) {
    setBusyAddressId(address.id)
    try {
      await adminApi.customers.deleteAddress(customer.id, address.id)
      await refreshDetail()
      toast.success('Dirección eliminada')
    } catch {
      toast.error('No se pudo eliminar la dirección')
    } finally {
      setBusyAddressId(null)
    }
  }

  const statCards = [
    { label: 'Órdenes totales', value: customer.stats.totalOrders },
    { label: 'Órdenes completadas', value: customer.stats.completedOrders },
    { label: 'Total gastado', value: formatCurrency(customer.stats.totalSpent) },
    { label: 'Ticket promedio', value: formatCurrency(customer.stats.averageOrder) },
  ]

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center gap-3">
        <Button variant="ghost" size="icon" onClick={onBack}>
          <ArrowLeft className="size-4" />
        </Button>
        <h1 className="text-2xl font-semibold">
          {customer.firstName} {customer.lastName}
        </h1>
        <Badge variant={customer.accountStatus === 'ACTIVE' ? 'default' : 'secondary'}>
          {STATUS_LABEL[customer.accountStatus] ?? customer.accountStatus}
        </Badge>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Datos personales</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4 text-sm sm:grid-cols-2">
          <div>
            <p className="text-muted-foreground">Correo</p>
            <p className="font-medium">{customer.email}</p>
          </div>
          <div>
            <p className="text-muted-foreground">Teléfono</p>
            <p className="font-medium">{customer.phone ?? '—'}</p>
          </div>
        </CardContent>
      </Card>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {statCards.map((card) => (
          <Card key={card.label}>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm font-medium text-muted-foreground">{card.label}</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold">{card.value}</div>
            </CardContent>
          </Card>
        ))}
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Direcciones</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          {customer.addresses.length === 0 && <p className="text-sm text-muted-foreground">Sin direcciones registradas.</p>}
          {customer.addresses.map((address) => (
            <div key={address.id} className="flex items-start justify-between gap-4 rounded-md border p-3">
              <div className="text-sm">
                <p className="font-medium">
                  {address.firstName} {address.lastName}
                  {address.isDefault && (
                    <Badge variant="outline" className="ml-2">
                      Predeterminada
                    </Badge>
                  )}
                </p>
                <p className="text-muted-foreground">
                  {address.addressLine1}
                  {address.addressLine2 ? `, ${address.addressLine2}` : ''}, {address.city}
                  {address.state ? `, ${address.state}` : ''} {address.postalCode ?? ''}
                </p>
                <p className="text-muted-foreground">{address.country}</p>
              </div>
              <div className="flex shrink-0 gap-2">
                {!address.isDefault && (
                  <Button
                    variant="ghost"
                    size="icon"
                    disabled={busyAddressId === address.id}
                    onClick={() => handleSetDefault(address)}
                    title="Marcar como predeterminada"
                  >
                    <Star className="size-4" />
                  </Button>
                )}
                <AlertDialog>
                  <AlertDialogTrigger asChild>
                    <Button variant="ghost" size="icon" disabled={busyAddressId === address.id}>
                      <Trash2 className="size-4 text-destructive" />
                    </Button>
                  </AlertDialogTrigger>
                  <AlertDialogContent>
                    <AlertDialogHeader>
                      <AlertDialogTitle>¿Eliminar dirección?</AlertDialogTitle>
                      <AlertDialogDescription>Esta acción eliminará la dirección permanentemente.</AlertDialogDescription>
                    </AlertDialogHeader>
                    <AlertDialogFooter>
                      <AlertDialogCancel>Cancelar</AlertDialogCancel>
                      <AlertDialogAction onClick={() => handleDeleteAddress(address)}>Eliminar</AlertDialogAction>
                    </AlertDialogFooter>
                  </AlertDialogContent>
                </AlertDialog>
              </div>
            </div>
          ))}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Historial de compras</CardTitle>
        </CardHeader>
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Orden</TableHead>
                <TableHead>Total</TableHead>
                <TableHead>Estado</TableHead>
                <TableHead>Fecha</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {ordersLoading && (
                <TableRow>
                  <TableCell colSpan={4} className="py-8 text-center text-muted-foreground">
                    Cargando…
                  </TableCell>
                </TableRow>
              )}
              {!ordersLoading && orders?.content.length === 0 && (
                <TableRow>
                  <TableCell colSpan={4} className="py-8 text-center text-muted-foreground">
                    Sin órdenes.
                  </TableCell>
                </TableRow>
              )}
              {!ordersLoading &&
                orders?.content.map((order) => (
                  <TableRow key={order.id}>
                    <TableCell>
                      <Link to={`/admin/orders/${order.id}`} className="font-medium hover:underline">
                        {order.orderNumber}
                      </Link>
                    </TableCell>
                    <TableCell>{formatCurrency(order.total)}</TableCell>
                    <TableCell>
                      <Badge variant="outline">{order.status}</Badge>
                    </TableCell>
                    <TableCell className="text-muted-foreground">{new Date(order.createdAt).toLocaleDateString('es-CL')}</TableCell>
                  </TableRow>
                ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {orders && (
        <DataTablePagination
          page={ordersPage}
          totalPages={orders.totalPages}
          totalElements={orders.totalElements}
          onPageChange={setOrdersPage}
        />
      )}
    </div>
  )
}
