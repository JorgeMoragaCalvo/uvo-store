import { useEffect } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { ArrowDown, ArrowUp, Pencil, Plus, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
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
import { useAdminBannersStore } from '@/admin/stores/useAdminBannersStore'
import type { HomeBannerDto } from '@/admin/types/admin'

export default function BannersList() {
  const banners = useAdminBannersStore((state) => state.banners)
  const loading = useAdminBannersStore((state) => state.loading)
  const fetch = useAdminBannersStore((state) => state.fetch)
  const remove = useAdminBannersStore((state) => state.remove)
  const toggle = useAdminBannersStore((state) => state.toggle)
  const reorder = useAdminBannersStore((state) => state.reorder)

  useEffect(() => {
    fetch().catch(() => toast.error('No se pudieron cargar los banners'))
  }, [fetch])

  async function handleDelete(banner: HomeBannerDto) {
    try {
      await remove(banner.id)
      toast.success('Banner eliminado')
    } catch {
      toast.error('No se pudo eliminar el banner')
    }
  }

  async function handleToggle(banner: HomeBannerDto) {
    try {
      await toggle(banner.id)
    } catch {
      toast.error('No se pudo cambiar el estado del banner')
    }
  }

  async function move(index: number, direction: -1 | 1) {
    const target = index + direction
    if (target < 0 || target >= banners.length) return
    const orderedIds = banners.map((b) => b.id)
    const [item] = orderedIds.splice(index, 1)
    orderedIds.splice(target, 0, item)
    try {
      await reorder(orderedIds)
    } catch {
      toast.error('No se pudo reordenar')
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Banners del home</h1>
        <Button asChild>
          <Link to="/admin/banners/new">
            <Plus className="size-4" />
            Nuevo banner
          </Link>
        </Button>
      </div>

      <div className="rounded-lg border bg-background">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Orden</TableHead>
              <TableHead>Banner</TableHead>
              <TableHead>Título</TableHead>
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
            {!loading && banners.length === 0 && (
              <TableRow>
                <TableCell colSpan={5} className="py-8 text-center text-muted-foreground">
                  No hay banners.
                </TableCell>
              </TableRow>
            )}
            {!loading &&
              banners.map((banner, index) => (
                <TableRow key={banner.id}>
                  <TableCell>
                    <div className="flex gap-1">
                      <Button variant="ghost" size="icon" disabled={index === 0} onClick={() => move(index, -1)}>
                        <ArrowUp className="size-4" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        disabled={index === banners.length - 1}
                        onClick={() => move(index, 1)}
                      >
                        <ArrowDown className="size-4" />
                      </Button>
                    </div>
                  </TableCell>
                  <TableCell>
                    {banner.image ? (
                      <img src={banner.image} alt={banner.title ?? ''} loading="lazy" className="h-10 w-16 rounded object-cover" />
                    ) : (
                      <div className="h-10 w-16 rounded bg-muted" />
                    )}
                  </TableCell>
                  <TableCell className="font-medium">{banner.title || '(sin título)'}</TableCell>
                  <TableCell>
                    <Badge variant={banner.active ? 'default' : 'secondary'} className="cursor-pointer" onClick={() => handleToggle(banner)}>
                      {banner.active ? 'Activo' : 'Inactivo'}
                    </Badge>
                  </TableCell>
                  <TableCell>
                    <div className="flex justify-end gap-2">
                      <Button variant="ghost" size="icon" asChild>
                        <Link to={`/admin/banners/${banner.id}/edit`}>
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
                            <AlertDialogTitle>¿Eliminar banner?</AlertDialogTitle>
                            <AlertDialogDescription>Esta acción no se puede deshacer.</AlertDialogDescription>
                          </AlertDialogHeader>
                          <AlertDialogFooter>
                            <AlertDialogCancel>Cancelar</AlertDialogCancel>
                            <AlertDialogAction onClick={() => handleDelete(banner)}>Eliminar</AlertDialogAction>
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
