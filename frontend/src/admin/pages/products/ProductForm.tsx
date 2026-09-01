import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Switch } from '@/components/ui/switch'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import adminApi from '@/admin/services/adminApi'
import type { CategoryDto, ProductImageDto } from '@/admin/types/admin'

interface FormState {
  name: string
  shortDescription: string
  description: string
  categoryId: string
  sku: string
  price: string
  stock: string
  manageStock: boolean
  active: boolean
  isFeatured: boolean
}

const EMPTY_FORM: FormState = {
  name: '',
  shortDescription: '',
  description: '',
  categoryId: '',
  sku: '',
  price: '',
  stock: '0',
  manageStock: true,
  active: true,
  isFeatured: false,
}

export default function ProductForm() {
  const { id } = useParams()
  const isEdit = Boolean(id)
  const navigate = useNavigate()

  const [categories, setCategories] = useState<CategoryDto[]>([])
  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [featuredImage, setFeaturedImage] = useState<File | null>(null)
  const [galleryImages, setGalleryImages] = useState<File[]>([])
  // Kept as full DTOs, not just urls: `id` is what the delete endpoint needs and `isFeatured` is
  // what tells the admin which photo the storefront and the product listing actually show.
  const [existingImages, setExistingImages] = useState<ProductImageDto[]>([])
  const [loading, setLoading] = useState(isEdit)
  const [saving, setSaving] = useState(false)
  const [removingImageId, setRemovingImageId] = useState<number | null>(null)

  useEffect(() => {
    adminApi.categories.list().then(setCategories).catch(() => toast.error('No se pudieron cargar las categorías'))
  }, [])

  useEffect(() => {
    if (!id) return
    adminApi.products
      .getById(Number(id))
      .then((product) => {
        setForm({
          name: product.name,
          shortDescription: product.shortDescription ?? '',
          description: product.description ?? '',
          categoryId: product.category ? String(product.category.id) : '',
          sku: product.sku ?? '',
          price: product.price != null ? String(product.price) : '',
          stock: String(product.stock),
          manageStock: product.manageStock,
          active: product.active,
          isFeatured: product.featured,
        })
        setExistingImages(product.images)
      })
      .catch(() => toast.error('No se pudo cargar el producto'))
      .finally(() => setLoading(false))
  }, [id])

  function buildFormData(): FormData {
    const data = new FormData()
    data.append('productType', 'simple')
    data.append('name', form.name)
    data.append('shortDescription', form.shortDescription)
    data.append('description', form.description)
    if (form.categoryId) data.append('categoryId', form.categoryId)
    data.append('active', String(form.active))
    data.append('isFeatured', String(form.isFeatured))
    data.append('isNew', 'false')
    data.append('sortOrder', '0')
    data.append('isOnSale', 'false')
    data.append('sku', form.sku)
    data.append('price', form.price || '0')
    data.append('stock', form.stock || '0')
    data.append('manageStock', String(form.manageStock))
    // Sent on both create and update: PUT /api/admin/products/{id} accepts them and
    // ProductServiceImpl.updateProduct persists them, so photos can be added or replaced after
    // creation. Empty inputs mean nothing is sent, and nothing changes.
    if (featuredImage) data.append('featuredImage', featuredImage)
    galleryImages.forEach((file) => data.append('images', file))
    return data
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    try {
      const data = buildFormData()
      if (isEdit) {
        await adminApi.products.update(Number(id), data)
        toast.success('Producto actualizado')
      } else {
        await adminApi.products.create(data)
        toast.success('Producto creado')
      }
      navigate('/admin/products')
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudo guardar el producto')
    } finally {
      setSaving(false)
    }
  }

  async function handleRemoveImage(imageId: number) {
    if (!id) return
    setRemovingImageId(imageId)
    try {
      // The endpoint returns the refreshed product, so the gallery (including which image was
      // promoted to featured after deleting the featured one) comes straight from the response.
      const product = await adminApi.products.removeImage(Number(id), imageId)
      setExistingImages(product.images)
      toast.success('Imagen eliminada')
    } catch {
      toast.error('No se pudo eliminar la imagen')
    } finally {
      setRemovingImageId(null)
    }
  }

  if (loading) {
    return <p className="text-muted-foreground">Cargando…</p>
  }

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{isEdit ? 'Editar producto' : 'Nuevo producto'}</h1>

      <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
        <Card>
          <CardHeader>
            <CardTitle>Información general</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor="name">Nombre</Label>
              <Input
                id="name"
                required
                value={form.name}
                onChange={(event) => setForm({ ...form, name: event.target.value })}
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="shortDescription">Descripción corta</Label>
              <Input
                id="shortDescription"
                value={form.shortDescription}
                onChange={(event) => setForm({ ...form, shortDescription: event.target.value })}
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="description">Descripción</Label>
              <Textarea
                id="description"
                rows={4}
                value={form.description}
                onChange={(event) => setForm({ ...form, description: event.target.value })}
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label>Categoría</Label>
              <Select
                value={form.categoryId || undefined}
                onValueChange={(value) => setForm({ ...form, categoryId: value })}
              >
                <SelectTrigger>
                  <SelectValue placeholder="Sin categoría" />
                </SelectTrigger>
                <SelectContent>
                  {categories.map((category) => (
                    <SelectItem key={category.id} value={String(category.id)}>
                      {category.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Precio e inventario</CardTitle>
          </CardHeader>
          <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-2">
              <Label htmlFor="sku">SKU</Label>
              <Input id="sku" value={form.sku} onChange={(event) => setForm({ ...form, sku: event.target.value })} />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="price">Precio</Label>
              <Input
                id="price"
                type="number"
                min="0"
                step="1"
                value={form.price}
                onChange={(event) => setForm({ ...form, price: event.target.value })}
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="stock">Stock</Label>
              <Input
                id="stock"
                type="number"
                min="0"
                step="1"
                value={form.stock}
                onChange={(event) => setForm({ ...form, stock: event.target.value })}
              />
            </div>
            <div className="flex items-center justify-between rounded-md border px-3 py-2">
              <Label htmlFor="manageStock">Gestionar stock</Label>
              <Switch
                id="manageStock"
                checked={form.manageStock}
                onCheckedChange={(checked) => setForm({ ...form, manageStock: checked })}
              />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Visibilidad</CardTitle>
          </CardHeader>
          <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div className="flex items-center justify-between rounded-md border px-3 py-2">
              <Label htmlFor="active">Activo</Label>
              <Switch id="active" checked={form.active} onCheckedChange={(checked) => setForm({ ...form, active: checked })} />
            </div>
            <div className="flex items-center justify-between rounded-md border px-3 py-2">
              <Label htmlFor="isFeatured">Destacado</Label>
              <Switch
                id="isFeatured"
                checked={form.isFeatured}
                onCheckedChange={(checked) => setForm({ ...form, isFeatured: checked })}
              />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Imágenes</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            {existingImages.length > 0 && (
              <div className="flex flex-wrap gap-3">
                {existingImages.map((image) => (
                  <div key={image.id} className="flex flex-col items-center gap-1">
                    <img src={image.url} alt={image.alt ?? ''} className="size-20 rounded object-cover" />
                    {image.isFeatured && (
                      <span className="text-xs text-muted-foreground">Destacada</span>
                    )}
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      disabled={removingImageId === image.id}
                      onClick={() => handleRemoveImage(image.id)}
                    >
                      {removingImageId === image.id ? 'Quitando…' : 'Quitar'}
                    </Button>
                  </div>
                ))}
              </div>
            )}
            <div className="flex flex-col gap-2">
              <Label htmlFor="featuredImage">
                {existingImages.some((image) => image.isFeatured) ? 'Reemplazar destacada' : 'Imagen destacada'}
              </Label>
              <Input
                id="featuredImage"
                type="file"
                accept="image/*"
                onChange={(event) => setFeaturedImage(event.target.files?.[0] ?? null)}
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="images">{isEdit ? 'Añadir a galería' : 'Galería'}</Label>
              <Input
                id="images"
                type="file"
                accept="image/*"
                multiple
                onChange={(event) => setGalleryImages(Array.from(event.target.files ?? []))}
              />
            </div>
          </CardContent>
        </Card>

        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={() => navigate('/admin/products')}>
            Cancelar
          </Button>
          <Button type="submit" disabled={saving}>
            {saving ? 'Guardando…' : 'Guardar'}
          </Button>
        </div>
      </form>
    </div>
  )
}
