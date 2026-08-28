import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { Search, Trash2, Users } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
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
import type { AdminCustomerStatsDto, AdminCustomerSummaryDto } from '@/admin/types/admin'

const STATUS_LABEL: Record<string, string> = {
  GUEST: 'Invitado',
  INVITED: 'Invitado con cuenta',
  ACTIVE: 'Activo',
}

export default function CustomersList() {
  const data = useAdminCustomersStore((state) => state.data)
  const loading = useAdminCustomersStore((state) => state.loading)
  const fetchList = useAdminCustomersStore((state) => state.fetchList)
  const remove = useAdminCustomersStore((state) => state.remove)

  const [search, setSearch] = useState('')
  const [page, setPage] = useState(1)
  const [stats, setStats] = useState<AdminCustomerStatsDto | null>(null)

  useEffect(() => {
    adminApi.customers.stats().then(setStats).catch(() => setStats(null))
  }, [])

  useEffect(() => {
    fetchList({ search: search || undefined, page }).catch(() => toast.error('No se pudieron cargar los clientes'))
  }, [fetchList, search, page])

  async function handleDelete(customer: AdminCustomerSummaryDto) {
    try {
      await remove(customer.id)
      toast.success('Cliente eliminado')
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudo eliminar el cliente')
    }
  }

  const statCards = [
    { label: 'Clientes totales', value: stats?.totalCustomers },
    { label: 'Con órdenes', value: stats?.withOrders },
    { label: 'Nuevos este mes', value: stats?.newThisMonth },
  ]

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">Clientes</h1>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        {statCards.map((card) => (
          <Card key={card.label}>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium text-muted-foreground">{card.label}</CardTitle>
              <Users className="size-4 text-muted-foreground" />
            </CardHeader>
            <CardContent>
              {card.value === undefined ? <Skeleton className="h-8 w-16" /> : <div className="text-2xl font-bold">{card.value}</div>}
            </CardContent>
          </Card>
        ))}
      </div>

      <div className="relative w-full max-w-sm">
        <Search className="absolute top-2.5 left-2.5 size-4 text-muted-foreground" />
        <Input
          placeholder="Buscar por nombre, email o teléfono…"
          className="pl-8"
          value={search}
          onChange={(event) => {
            setPage(1)
            setSearch(event.target.value)
          }}
        />
      </div>

      <div className="rounded-lg border bg-background">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Cliente</TableHead>
              <TableHead>Teléfono</TableHead>
              <TableHead>Estado</TableHead>
              <TableHead>Órdenes</TableHead>
              <TableHead>Alta</TableHead>
              <TableHead className="text-right">Acciones</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {loading && (
              <TableRow>
                <TableCell colSpan={6} className="py-8 text-center text-muted-foreground">
                  Cargando…
                </TableCell>
              </TableRow>
            )}
            {!loading && data?.content.length === 0 && (
              <TableRow>
                <TableCell colSpan={6} className="py-8 text-center text-muted-foreground">
                  No hay clientes.
                </TableCell>
              </TableRow>
            )}
            {!loading &&
              data?.content.map((customer) => (
                <TableRow key={customer.id}>
                  <TableCell>
                    <Link to={`/admin/customers/${customer.id}`} className="font-medium hover:underline">
                      {customer.firstName} {customer.lastName}
                    </Link>
                    <div className="text-sm text-muted-foreground">{customer.email}</div>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{customer.phone ?? '—'}</TableCell>
                  <TableCell>
                    <Badge variant={customer.accountStatus === 'ACTIVE' ? 'default' : 'secondary'}>
                      {STATUS_LABEL[customer.accountStatus] ?? customer.accountStatus}
                    </Badge>
                  </TableCell>
                  <TableCell>{customer.ordersCount}</TableCell>
                  <TableCell className="text-muted-foreground">{new Date(customer.createdAt).toLocaleDateString('es-CL')}</TableCell>
                  <TableCell>
                    <div className="flex justify-end gap-2">
                      <AlertDialog>
                        <AlertDialogTrigger asChild>
                          <Button variant="ghost" size="icon">
                            <Trash2 className="size-4 text-destructive" />
                          </Button>
                        </AlertDialogTrigger>
                        <AlertDialogContent>
                          <AlertDialogHeader>
                            <AlertDialogTitle>¿Eliminar cliente?</AlertDialogTitle>
                            <AlertDialogDescription>
                              Esta acción eliminará a «{customer.firstName} {customer.lastName}» permanentemente. No se puede
                              eliminar un cliente con órdenes asociadas.
                            </AlertDialogDescription>
                          </AlertDialogHeader>
                          <AlertDialogFooter>
                            <AlertDialogCancel>Cancelar</AlertDialogCancel>
                            <AlertDialogAction onClick={() => handleDelete(customer)}>Eliminar</AlertDialogAction>
                          </AlertDialogFooter>
                        </AlertDialogContent>
                      </AlertDialog>
                    </div>
                  </TableCell>
                </TableRow>
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
