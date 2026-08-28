import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { Pencil, Plus, Search, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { Switch } from '@/components/ui/switch'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
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
import { useAdminCouponsStore } from '@/admin/stores/useAdminCouponsStore'
import type { CouponDto } from '@/admin/types/admin'

const STATUS_OPTIONS = [
  { value: 'all', label: 'Todos' },
  { value: 'active', label: 'Activos' },
  { value: 'inactive', label: 'Inactivos' },
  { value: 'expired', label: 'Vencidos' },
]

function formatValue(coupon: CouponDto) {
  return coupon.type === 'percentage' ? `${coupon.value}%` : `$${coupon.value}`
}

function formatDate(value: string | null) {
  return value ? new Date(value).toLocaleDateString('es-CL') : '—'
}

export default function CouponsList() {
  const data = useAdminCouponsStore((state) => state.data)
  const loading = useAdminCouponsStore((state) => state.loading)
  const fetchList = useAdminCouponsStore((state) => state.fetchList)
  const remove = useAdminCouponsStore((state) => state.remove)
  const toggleStatus = useAdminCouponsStore((state) => state.toggleStatus)

  const [search, setSearch] = useState('')
  const [status, setStatus] = useState('all')
  const [page, setPage] = useState(1)

  useEffect(() => {
    fetchList({ search: search || undefined, status, page }).catch(() => toast.error('No se pudieron cargar los cupones'))
  }, [fetchList, search, status, page])

  async function handleDelete(coupon: CouponDto) {
    try {
      await remove(coupon.id)
      toast.success('Cupón eliminado')
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudo eliminar el cupón')
    }
  }

  async function handleToggle(coupon: CouponDto) {
    try {
      await toggleStatus(coupon.id)
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudo cambiar el estado del cupón')
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Cupones</h1>
        <Button asChild>
          <Link to="/admin/coupons/new">
            <Plus className="size-4" />
            Nuevo cupón
          </Link>
        </Button>
      </div>

      <div className="flex flex-wrap gap-3">
        <div className="relative w-full max-w-sm">
          <Search className="absolute top-2.5 left-2.5 size-4 text-muted-foreground" />
          <Input
            placeholder="Buscar por código o nombre…"
            className="pl-8"
            value={search}
            onChange={(event) => {
              setPage(1)
              setSearch(event.target.value)
            }}
          />
        </div>
        <Select
          value={status}
          onValueChange={(value) => {
            setPage(1)
            setStatus(value)
          }}
        >
          <SelectTrigger className="w-40">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {STATUS_OPTIONS.map((option) => (
              <SelectItem key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="rounded-lg border bg-background">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Código</TableHead>
              <TableHead>Nombre</TableHead>
              <TableHead>Valor</TableHead>
              <TableHead>Vigencia</TableHead>
              <TableHead>Usos</TableHead>
              <TableHead>Estado</TableHead>
              <TableHead className="text-right">Acciones</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {loading && (
              <TableRow>
                <TableCell colSpan={7} className="py-8 text-center text-muted-foreground">
                  Cargando…
                </TableCell>
              </TableRow>
            )}
            {!loading && data?.content.length === 0 && (
              <TableRow>
                <TableCell colSpan={7} className="py-8 text-center text-muted-foreground">
                  No hay cupones.
                </TableCell>
              </TableRow>
            )}
            {!loading &&
              data?.content.map((coupon) => (
                <TableRow key={coupon.id}>
                  <TableCell className="font-medium">{coupon.code}</TableCell>
                  <TableCell className="text-muted-foreground">{coupon.name}</TableCell>
                  <TableCell>{formatValue(coupon)}</TableCell>
                  <TableCell className="text-muted-foreground">
                    {formatDate(coupon.startsAt)} – {formatDate(coupon.expiresAt)}
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {coupon.timesUsed}
                    {coupon.usageLimit ? ` / ${coupon.usageLimit}` : ''}
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-2">
                      <Switch checked={coupon.active} onCheckedChange={() => handleToggle(coupon)} />
                      <Badge variant={coupon.active ? 'default' : 'secondary'}>
                        {coupon.active ? 'Activo' : 'Inactivo'}
                      </Badge>
                    </div>
                  </TableCell>
                  <TableCell>
                    <div className="flex justify-end gap-2">
                      <Button variant="ghost" size="icon" asChild>
                        <Link to={`/admin/coupons/${coupon.id}/edit`}>
                          <Pencil className="size-4" />
                        </Link>
                      </Button>
                      <AlertDialog>
                        <AlertDialogTrigger asChild>
                          <Button variant="ghost" size="icon">
                            <Trash2 className="size-4 text-destructive" />
                          </Button>
                        </AlertDialogTrigger>
                        <AlertDialogContent>
                          <AlertDialogHeader>
                            <AlertDialogTitle>¿Eliminar cupón?</AlertDialogTitle>
                            <AlertDialogDescription>
                              Esta acción eliminará «{coupon.code}» permanentemente.
                            </AlertDialogDescription>
                          </AlertDialogHeader>
                          <AlertDialogFooter>
                            <AlertDialogCancel>Cancelar</AlertDialogCancel>
                            <AlertDialogAction onClick={() => handleDelete(coupon)}>Eliminar</AlertDialogAction>
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
