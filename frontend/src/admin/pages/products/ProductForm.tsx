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
import type { CategoryDto } from '@/admin/types/admin'

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
  const [existingImages, setExistingImages] = useState<string[]>([])
  const [loading, setLoading] = useState(isEdit)
  const [saving, setSaving] = useState(false)

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
        setExistingImages(product.images.map((image) => image.url))
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
    if (!isEdit) {
      if (featuredImage) data.append('featuredImage', featuredImage)
      galleryImages.forEach((file) => data.append('images', file))
    }
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

        {isEdit ? (
          existingImages.length > 0 && (
            <Card>
              <CardHeader>
                <CardTitle>Imágenes actuales</CardTitle>
              </CardHeader>
              <CardContent className="flex flex-wrap gap-3">
                {existingImages.map((url) => (
                  <img key={url} src={url} alt="" className="size-20 rounded object-cover" />
                ))}
              </CardContent>
            </Card>
          )
        ) : (
          <Card>
            <CardHeader>
              <CardTitle>Imágenes</CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-4">
              <div className="flex flex-col gap-2">
                <Label htmlFor="featuredImage">Imagen destacada</Label>
                <Input
                  id="featuredImage"
                  type="file"
                  accept="image/*"
                  onChange={(event) => setFeaturedImage(event.target.files?.[0] ?? null)}
                />
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="images">Galería</Label>
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
        )}

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
