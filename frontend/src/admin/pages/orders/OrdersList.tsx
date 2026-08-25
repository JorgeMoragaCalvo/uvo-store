import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { Search } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import DataTablePagination from '@/admin/components/DataTablePagination'
import { useAdminOrdersStore } from '@/admin/stores/useAdminOrdersStore'

const TABS = [
  { value: 'all', label: 'Todas' },
  { value: 'pending', label: 'Pendientes' },
  { value: 'paid', label: 'Pagadas' },
  { value: 'processing', label: 'En proceso' },
  { value: 'shipped', label: 'Enviadas' },
  { value: 'cancelled', label: 'Canceladas' },
]

const STATUS_VARIANT: Record<string, 'default' | 'secondary' | 'destructive' | 'outline'> = {
  PENDING: 'outline',
  PROCESSING: 'secondary',
  SHIPPED: 'secondary',
  DELIVERED: 'default',
  CANCELLED: 'destructive',
}

function formatCurrency(value: number) {
  return new Intl.NumberFormat('es-CL', { style: 'currency', currency: 'CLP' }).format(value)
}

export default function OrdersList() {
  const data = useAdminOrdersStore((state) => state.data)
  const loading = useAdminOrdersStore((state) => state.loading)
  const fetchList = useAdminOrdersStore((state) => state.fetchList)

  const [tab, setTab] = useState('all')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(1)

  useEffect(() => {
    fetchList({ tab, search: search || undefined, page }).catch(() => toast.error('No se pudieron cargar las órdenes'))
  }, [fetchList, tab, search, page])

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">Órdenes</h1>

      <Tabs
        value={tab}
        onValueChange={(value) => {
          setPage(1)
          setTab(value)
        }}
      >
        <TabsList>
          {TABS.map((item) => (
            <TabsTrigger key={item.value} value={item.value}>
              {item.label}
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>

      <div className="relative w-full max-w-sm">
        <Search className="absolute top-2.5 left-2.5 size-4 text-muted-foreground" />
        <Input
          placeholder="Buscar por N° de orden, cliente…"
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
              <TableHead>Orden</TableHead>
              <TableHead>Cliente</TableHead>
              <TableHead>Total</TableHead>
              <TableHead>Estado</TableHead>
              <TableHead>Pago</TableHead>
              <TableHead>Fecha</TableHead>
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
                  No hay órdenes.
                </TableCell>
              </TableRow>
            )}
            {!loading &&
              data?.content.map((order) => (
                <TableRow key={order.id} className="cursor-pointer">
                  <TableCell>
                    <Link to={`/admin/orders/${order.id}`} className="font-medium hover:underline">
                      {order.orderNumber}
                    </Link>
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {order.customerFirstName} {order.customerLastName}
                  </TableCell>
                  <TableCell>{formatCurrency(order.total)}</TableCell>
                  <TableCell>
                    <Badge variant={STATUS_VARIANT[order.status] ?? 'outline'}>{order.status}</Badge>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{order.paymentStatus}</TableCell>
                  <TableCell className="text-muted-foreground">
                    {new Date(order.createdAt).toLocaleDateString('es-CL')}
                  </TableCell>
                </TableRow>
              ))}
          </TableBody>
        </Table>
      </div>

      {data && (
        <DataTablePagination
          page={page}
          totalPages={data.totalPages}
          totalElements={data.totalElements}
          onPageChange={setPage}
        />
      )}
    </div>
  )
}
