import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { Pencil, Plus, Search, Trash2 } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Switch } from '@/components/ui/switch'
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
import { useAdminShippingZonesStore } from '@/admin/stores/useAdminShippingZonesStore'
import type { ShippingZoneDto } from '@/admin/types/admin'

export default function ZonesList() {
  const zones = useAdminShippingZonesStore((state) => state.zones)
  const loading = useAdminShippingZonesStore((state) => state.loading)
  const fetch = useAdminShippingZonesStore((state) => state.fetch)
  const remove = useAdminShippingZonesStore((state) => state.remove)
  const toggleStatus = useAdminShippingZonesStore((state) => state.toggleStatus)

  const [search, setSearch] = useState('')

  useEffect(() => {
    fetch(search || undefined).catch(() => toast.error('No se pudieron cargar las zonas'))
  }, [fetch, search])

  async function handleDelete(zone: ShippingZoneDto) {
    try {
      await remove(zone.id)
      toast.success('Zona eliminada')
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudo eliminar la zona')
    }
  }

  async function handleToggle(zone: ShippingZoneDto) {
    try {
      await toggleStatus(zone.id)
    } catch {
      toast.error('No se pudo cambiar el estado de la zona')
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Zonas de envío</h1>
        <Button asChild>
          <Link to="/admin/shipping/zones/new">
            <Plus className="size-4" />
            Nueva zona
          </Link>
        </Button>
      </div>

      <div className="relative w-full max-w-sm">
        <Search className="absolute top-2.5 left-2.5 size-4 text-muted-foreground" />
        <Input placeholder="Buscar por nombre…" className="pl-8" value={search} onChange={(event) => setSearch(event.target.value)} />
      </div>

      <div className="rounded-lg border bg-background">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Zona</TableHead>
              <TableHead>Regiones</TableHead>
              <TableHead>Comunas</TableHead>
              <TableHead>Estado</TableHead>
              <TableHead className="text-right">Acciones</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {loading && (
              <TableRow>
                <TableCell colSpan={5} className="py-8 text-center text-muted-foreground">
                  Cargando…
                </TableCell>
              </TableRow>
            )}
            {!loading && zones.length === 0 && (
              <TableRow>
                <TableCell colSpan={5} className="py-8 text-center text-muted-foreground">
                  No hay zonas de envío.
                </TableCell>
              </TableRow>
            )}
            {!loading &&
              zones.map((zone) => (
                <TableRow key={zone.id}>
                  <TableCell className="font-medium">{zone.name}</TableCell>
                  <TableCell className="text-muted-foreground">{zone.regions.length} región(es)</TableCell>
                  <TableCell className="text-muted-foreground">
                    {zone.communes.length > 0 ? `${zone.communes.length} comuna(s)` : 'Toda la región'}
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-2">
                      <Switch checked={zone.active} onCheckedChange={() => handleToggle(zone)} />
                      <Badge variant={zone.active ? 'default' : 'secondary'}>{zone.active ? 'Activa' : 'Inactiva'}</Badge>
                    </div>
                  </TableCell>
                  <TableCell>
                    <div className="flex justify-end gap-2">
                      <Button variant="ghost" size="icon" asChild>
                        <Link to={`/admin/shipping/zones/${zone.id}/edit`}>
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
                            <AlertDialogTitle>¿Eliminar zona?</AlertDialogTitle>
                            <AlertDialogDescription>Esta acción eliminará «{zone.name}» permanentemente.</AlertDialogDescription>
                          </AlertDialogHeader>
                          <AlertDialogFooter>
                            <AlertDialogCancel>Cancelar</AlertDialogCancel>
                            <AlertDialogAction onClick={() => handleDelete(zone)}>Eliminar</AlertDialogAction>
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
