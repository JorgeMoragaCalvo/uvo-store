import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { Pencil, Plus, Search, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
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
import { useAdminProductsStore } from '@/admin/stores/useAdminProductsStore'
import type { ProductDto } from '@/admin/types/admin'

const ACTIVE_OPTIONS = [
  { value: 'all', label: 'Todos' },
  { value: 'true', label: 'Activos' },
  { value: 'false', label: 'Inactivos' },
]

export default function ProductsList() {
  const data = useAdminProductsStore((state) => state.data)
  const loading = useAdminProductsStore((state) => state.loading)
  const fetch = useAdminProductsStore((state) => state.fetch)
  const toggleActive = useAdminProductsStore((state) => state.toggleActive)
  const remove = useAdminProductsStore((state) => state.remove)

  const [search, setSearch] = useState('')
  const [activeFilter, setActiveFilter] = useState('all')
  const [page, setPage] = useState(1)

  useEffect(() => {
    fetch({
      search: search || undefined,
      active: activeFilter === 'all' ? undefined : activeFilter === 'true',
      page,
      perPage: 15,
    }).catch(() => toast.error('No se pudieron cargar los productos'))
  }, [fetch, search, activeFilter, page])

  async function handleToggleActive(product: ProductDto) {
    try {
      await toggleActive(product.id)
    } catch {
      toast.error('No se pudo cambiar el estado del producto')
    }
  }

  async function handleDelete(product: ProductDto) {
    try {
      await remove(product.id)
      toast.success('Producto eliminado')
    } catch {
      toast.error('No se pudo eliminar el producto')
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Productos</h1>
        <Button asChild>
          <Link to="/admin/products/new">
            <Plus className="size-4" />
            Nuevo producto
          </Link>
        </Button>
      </div>

      <div className="flex flex-wrap gap-3">
        <div className="relative w-full max-w-sm">
          <Search className="absolute top-2.5 left-2.5 size-4 text-muted-foreground" />
          <Input
            placeholder="Buscar por nombre, SKU…"
            className="pl-8"
            value={search}
            onChange={(event) => {
              setPage(1)
              setSearch(event.target.value)
            }}
          />
        </div>
        <Select
          value={activeFilter}
          onValueChange={(value) => {
            setPage(1)
            setActiveFilter(value)
          }}
        >
          <SelectTrigger className="w-40">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {ACTIVE_OPTIONS.map((option) => (
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
              <TableHead>Producto</TableHead>
              <TableHead>SKU</TableHead>
              <TableHead>Precio</TableHead>
              <TableHead>Stock</TableHead>
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
            {!loading && data?.content.length === 0 && (
              <TableRow>
                <TableCell colSpan={6} className="py-8 text-center text-muted-foreground">
                  No hay productos.
                </TableCell>
              </TableRow>
            )}
            {!loading &&
              data?.content.map((product) => (
                <TableRow key={product.id}>
                  <TableCell>
                    <div className="flex items-center gap-3">
                      {product.featuredImage ? (
                        <img src={product.featuredImage} alt={product.name} loading="lazy" className="size-10 rounded object-cover" />
                      ) : (
                        <div className="size-10 rounded bg-muted" />
                      )}
                      <span className="font-medium">{product.name}</span>
                    </div>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{product.sku ?? '—'}</TableCell>
                  <TableCell>{product.formattedPrice}</TableCell>
                  <TableCell>{product.productType === 'simple' ? product.stock : '—'}</TableCell>
                  <TableCell>
                    <Badge
                      variant={product.active ? 'default' : 'secondary'}
                      className="cursor-pointer"
                      onClick={() => handleToggleActive(product)}
                    >
                      {product.active ? 'Activo' : 'Inactivo'}
                    </Badge>
                  </TableCell>
                  <TableCell>
                    <div className="flex justify-end gap-2">
                      <Button variant="ghost" size="icon" asChild>
                        <Link to={`/admin/products/${product.id}/edit`}>
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
                            <AlertDialogTitle>¿Eliminar producto?</AlertDialogTitle>
                            <AlertDialogDescription>
                              Esta acción eliminará «{product.name}» permanentemente.
                            </AlertDialogDescription>
                          </AlertDialogHeader>
                          <AlertDialogFooter>
                            <AlertDialogCancel>Cancelar</AlertDialogCancel>
                            <AlertDialogAction onClick={() => handleDelete(product)}>Eliminar</AlertDialogAction>
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
