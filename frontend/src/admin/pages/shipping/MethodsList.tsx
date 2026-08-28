import { useEffect } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { Pencil, Plus, Trash2 } from 'lucide-react'
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
import { useAdminShippingMethodsStore } from '@/admin/stores/useAdminShippingMethodsStore'
import type { ShippingMethodDto } from '@/admin/types/admin'

const TYPE_LABEL: Record<string, string> = {
  COURIER: 'Courier',
  PICKUP: 'Retiro en tienda',
  CUSTOM: 'Personalizado',
}

export default function MethodsList() {
  const methods = useAdminShippingMethodsStore((state) => state.methods)
  const loading = useAdminShippingMethodsStore((state) => state.loading)
  const fetch = useAdminShippingMethodsStore((state) => state.fetch)
  const remove = useAdminShippingMethodsStore((state) => state.remove)
  const toggleStatus = useAdminShippingMethodsStore((state) => state.toggleStatus)

  useEffect(() => {
    fetch().catch(() => toast.error('No se pudieron cargar los métodos de envío'))
  }, [fetch])

  async function handleDelete(method: ShippingMethodDto) {
    try {
      await remove(method.id)
      toast.success('Método eliminado')
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudo eliminar el método')
    }
  }

  async function handleToggle(method: ShippingMethodDto) {
    try {
      await toggleStatus(method.id)
    } catch {
      toast.error('No se pudo cambiar el estado del método')
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Métodos de envío</h1>
        <Button asChild>
          <Link to="/admin/shipping/methods/new">
            <Plus className="size-4" />
            Nuevo método
          </Link>
        </Button>
      </div>

      <div className="rounded-lg border bg-background">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Método</TableHead>
              <TableHead>Tipo</TableHead>
              <TableHead>Integración</TableHead>
              <TableHead>Tarifas</TableHead>
              <TableHead>Estado</TableHead>
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
            {!loading && methods.length === 0 && (
              <TableRow>
                <TableCell colSpan={6} className="py-8 text-center text-muted-foreground">
                  No hay métodos de envío.
                </TableCell>
              </TableRow>
            )}
            {!loading &&
              methods.map((method) => (
                <TableRow key={method.id}>
                  <TableCell>
                    <p className="font-medium">{method.name}</p>
                    <p className="text-sm text-muted-foreground">{method.code}</p>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{TYPE_LABEL[method.type] ?? method.type}</TableCell>
                  <TableCell className="text-muted-foreground">
                    {method.hasApiIntegration ? (method.carrier ?? '—') : 'Manual'}
                  </TableCell>
                  <TableCell>{method.ratesCount}</TableCell>
                  <TableCell>
                    <div className="flex items-center gap-2">
                      <Switch checked={method.active} onCheckedChange={() => handleToggle(method)} />
                      <Badge variant={method.active ? 'default' : 'secondary'}>{method.active ? 'Activo' : 'Inactivo'}</Badge>
                    </div>
                  </TableCell>
                  <TableCell>
                    <div className="flex justify-end gap-2">
                      <Button variant="ghost" size="icon" asChild>
                        <Link to={`/admin/shipping/methods/${method.id}/edit`}>
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
                            <AlertDialogTitle>¿Eliminar método?</AlertDialogTitle>
                            <AlertDialogDescription>Esta acción eliminará «{method.name}» permanentemente.</AlertDialogDescription>
                          </AlertDialogHeader>
                          <AlertDialogFooter>
                            <AlertDialogCancel>Cancelar</AlertDialogCancel>
                            <AlertDialogAction onClick={() => handleDelete(method)}>Eliminar</AlertDialogAction>
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
