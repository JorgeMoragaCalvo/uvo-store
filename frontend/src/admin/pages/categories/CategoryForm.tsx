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
  description: string
  parentId: string
  active: boolean
  sortOrder: string
  isFeatured: boolean
}

const EMPTY_FORM: FormState = {
  name: '',
  description: '',
  parentId: '',
  active: true,
  sortOrder: '0',
  isFeatured: false,
}

export default function CategoryForm() {
  const { id } = useParams()
  const isEdit = Boolean(id)
  const navigate = useNavigate()

  const [categories, setCategories] = useState<CategoryDto[]>([])
  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [image, setImage] = useState<File | null>(null)
  const [currentImage, setCurrentImage] = useState<string | null>(null)
  const [loading, setLoading] = useState(isEdit)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    adminApi.categories.list().then(setCategories).catch(() => toast.error('No se pudieron cargar las categorías'))
  }, [])

  useEffect(() => {
    if (!id) return
    adminApi.categories
      .getById(Number(id))
      .then((category) => {
        setForm({
          name: category.name,
          description: category.description ?? '',
          parentId: category.parentId ? String(category.parentId) : '',
          active: category.active,
          sortOrder: String(category.sortOrder),
          isFeatured: false,
        })
        setCurrentImage(category.image)
      })
      .catch(() => toast.error('No se pudo cargar la categoría'))
      .finally(() => setLoading(false))
  }, [id])

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    try {
      const data = new FormData()
      data.append('name', form.name)
      data.append('description', form.description)
      if (form.parentId) data.append('parentId', form.parentId)
      data.append('active', String(form.active))
      data.append('sortOrder', form.sortOrder || '0')
      data.append('isFeatured', String(form.isFeatured))
      if (image) data.append('image', image)

      if (isEdit) {
        await adminApi.categories.update(Number(id), data)
        toast.success('Categoría actualizada')
      } else {
        await adminApi.categories.create(data)
        toast.success('Categoría creada')
      }
      navigate('/admin/categories')
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudo guardar la categoría')
    } finally {
      setSaving(false)
    }
  }

  async function handleRemoveImage() {
    if (!id) return
    try {
      await adminApi.categories.removeImage(Number(id))
      setCurrentImage(null)
      toast.success('Imagen eliminada')
    } catch {
      toast.error('No se pudo eliminar la imagen')
    }
  }

  if (loading) {
    return <p className="text-muted-foreground">Cargando…</p>
  }

  const otherCategories = categories.filter((c) => String(c.id) !== id)

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{isEdit ? 'Editar categoría' : 'Nueva categoría'}</h1>

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
              <Label htmlFor="description">Descripción</Label>
              <Textarea
                id="description"
                rows={3}
                value={form.description}
                onChange={(event) => setForm({ ...form, description: event.target.value })}
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label>Categoría padre</Label>
              <Select
                value={form.parentId || undefined}
                onValueChange={(value) => setForm({ ...form, parentId: value })}
              >
                <SelectTrigger>
                  <SelectValue placeholder="Sin categoría padre" />
                </SelectTrigger>
                <SelectContent>
                  {otherCategories.map((category) => (
                    <SelectItem key={category.id} value={String(category.id)}>
                      {category.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="sortOrder">Orden</Label>
              <Input
                id="sortOrder"
                type="number"
                value={form.sortOrder}
                onChange={(event) => setForm({ ...form, sortOrder: event.target.value })}
              />
            </div>
            <div className="flex items-center justify-between rounded-md border px-3 py-2">
              <Label htmlFor="active">Activa</Label>
              <Switch id="active" checked={form.active} onCheckedChange={(checked) => setForm({ ...form, active: checked })} />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Imagen</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            {currentImage && (
              <div className="flex items-center gap-3">
                <img src={currentImage} alt="" className="size-20 rounded object-cover" />
                <Button type="button" variant="outline" size="sm" onClick={handleRemoveImage}>
                  Quitar imagen
                </Button>
              </div>
            )}
            <div className="flex flex-col gap-2">
              <Label htmlFor="image">{currentImage ? 'Reemplazar imagen' : 'Imagen'}</Label>
              <Input id="image" type="file" accept="image/*" onChange={(event) => setImage(event.target.files?.[0] ?? null)} />
            </div>
          </CardContent>
        </Card>

        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={() => navigate('/admin/categories')}>
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
