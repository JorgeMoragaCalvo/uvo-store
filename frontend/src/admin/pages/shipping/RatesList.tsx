import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { Pencil, Plus, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
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
import adminApi from '@/admin/services/adminApi'
import { useAdminShippingRatesStore } from '@/admin/stores/useAdminShippingRatesStore'
import type { ShippingMethodDto, ShippingRateDto, ShippingZoneDto } from '@/admin/types/admin'

const RATE_TYPE_LABEL: Record<string, string> = {
  FLAT: 'Monto fijo',
  WEIGHT_BASED: 'Por peso',
  PRICE_BASED: 'Monto fijo',
  FREE: 'Gratis',
}

function formatCurrency(value: number) {
  return new Intl.NumberFormat('es-CL', { style: 'currency', currency: 'CLP' }).format(value)
}

function formatValue(rate: ShippingRateDto) {
  if (rate.rateType === 'FREE') return 'Gratis'
  if (rate.rateType === 'WEIGHT_BASED') {
    return `${formatCurrency(rate.baseWeightRate ?? 0)} + ${formatCurrency(rate.weightRatePerKg ?? 0)}/kg`
  }
  return formatCurrency(rate.flatRate ?? 0)
}

export default function RatesList() {
  const rates = useAdminShippingRatesStore((state) => state.rates)
  const loading = useAdminShippingRatesStore((state) => state.loading)
  const fetch = useAdminShippingRatesStore((state) => state.fetch)
  const remove = useAdminShippingRatesStore((state) => state.remove)
  const toggleStatus = useAdminShippingRatesStore((state) => state.toggleStatus)

  const [methods, setMethods] = useState<ShippingMethodDto[]>([])
  const [zones, setZones] = useState<ShippingZoneDto[]>([])
  const [methodId, setMethodId] = useState<string>('')
  const [zoneId, setZoneId] = useState<string>('')

  useEffect(() => {
    adminApi.shippingMethods.list().then(setMethods).catch(() => toast.error('No se pudieron cargar los métodos'))
    adminApi.shippingZones.list().then(setZones).catch(() => toast.error('No se pudieron cargar las zonas'))
  }, [])

  useEffect(() => {
    fetch({ methodId: methodId ? Number(methodId) : undefined, zoneId: zoneId ? Number(zoneId) : undefined }).catch(() =>
      toast.error('No se pudieron cargar las tarifas'),
    )
  }, [fetch, methodId, zoneId])

  async function handleDelete(rate: ShippingRateDto) {
    try {
      await remove(rate.id)
      toast.success('Tarifa eliminada')
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudo eliminar la tarifa')
    }
  }

  async function handleToggle(rate: ShippingRateDto) {
    try {
      await toggleStatus(rate.id)
    } catch {
      toast.error('No se pudo cambiar el estado de la tarifa')
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Tarifas de envío</h1>
        <Button asChild>
          <Link to="/admin/shipping/rates/new">
            <Plus className="size-4" />
            Nueva tarifa
          </Link>
        </Button>
      </div>

      <div className="flex flex-wrap gap-3">
        <Select value={methodId || 'all'} onValueChange={(value) => setMethodId(value === 'all' ? '' : value)}>
          <SelectTrigger className="w-56">
            <SelectValue placeholder="Todos los métodos" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">Todos los métodos</SelectItem>
            {methods.map((method) => (
              <SelectItem key={method.id} value={String(method.id)}>
                {method.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Select value={zoneId || 'all'} onValueChange={(value) => setZoneId(value === 'all' ? '' : value)}>
          <SelectTrigger className="w-56">
            <SelectValue placeholder="Todas las zonas" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">Todas las zonas</SelectItem>
            {zones.map((zone) => (
              <SelectItem key={zone.id} value={String(zone.id)}>
                {zone.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="rounded-lg border bg-background">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Tarifa</TableHead>
              <TableHead>Método</TableHead>
              <TableHead>Zona</TableHead>
              <TableHead>Tipo</TableHead>
              <TableHead>Valor</TableHead>
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
            {!loading && rates.length === 0 && (
              <TableRow>
                <TableCell colSpan={7} className="py-8 text-center text-muted-foreground">
                  No hay tarifas.
                </TableCell>
              </TableRow>
            )}
            {!loading &&
              rates.map((rate) => (
                <TableRow key={rate.id}>
                  <TableCell className="font-medium">{rate.name}</TableCell>
                  <TableCell className="text-muted-foreground">{rate.methodName}</TableCell>
                  <TableCell className="text-muted-foreground">{rate.zoneName}</TableCell>
                  <TableCell className="text-muted-foreground">{RATE_TYPE_LABEL[rate.rateType] ?? rate.rateType}</TableCell>
                  <TableCell>{formatValue(rate)}</TableCell>
                  <TableCell>
                    <div className="flex items-center gap-2">
                      <Switch checked={rate.active} onCheckedChange={() => handleToggle(rate)} />
                      <Badge variant={rate.active ? 'default' : 'secondary'}>{rate.active ? 'Activa' : 'Inactiva'}</Badge>
                    </div>
                  </TableCell>
                  <TableCell>
                    <div className="flex justify-end gap-2">
                      <Button variant="ghost" size="icon" asChild>
                        <Link to={`/admin/shipping/rates/${rate.id}/edit`}>
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
                            <AlertDialogTitle>¿Eliminar tarifa?</AlertDialogTitle>
                            <AlertDialogDescription>Esta acción eliminará «{rate.name}» permanentemente.</AlertDialogDescription>
                          </AlertDialogHeader>
                          <AlertDialogFooter>
                            <AlertDialogCancel>Cancelar</AlertDialogCancel>
                            <AlertDialogAction onClick={() => handleDelete(rate)}>Eliminar</AlertDialogAction>
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
    </div>
  )
}
