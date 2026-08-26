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

interface FormState {
  title: string
  subtitle: string
  description: string
  ctaText: string
  ctaLink: string
  ctaNewTab: boolean
  ctaSecondaryText: string
  ctaSecondaryLink: string
  textPosition: 'left' | 'center' | 'right'
  textColor: 'light' | 'dark'
  overlayColor: string
  overlayOpacity: string
  active: boolean
  sortOrder: string
}

const EMPTY_FORM: FormState = {
  title: '',
  subtitle: '',
  description: '',
  ctaText: '',
  ctaLink: '',
  ctaNewTab: false,
  ctaSecondaryText: '',
  ctaSecondaryLink: '',
  textPosition: 'left',
  textColor: 'light',
  overlayColor: '#000000',
  overlayOpacity: '40',
  active: true,
  sortOrder: '0',
}

export default function BannerForm() {
  const { id } = useParams()
  const isEdit = Boolean(id)
  const navigate = useNavigate()

  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [image, setImage] = useState<File | null>(null)
  const [mobileImage, setMobileImage] = useState<File | null>(null)
  const [currentImage, setCurrentImage] = useState<string | null>(null)
  const [currentMobileImage, setCurrentMobileImage] = useState<string | null>(null)
  const [loading, setLoading] = useState(isEdit)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!id) return
    adminApi.banners
      .getById(Number(id))
      .then((banner) => {
        setForm({
          title: banner.title ?? '',
          subtitle: banner.subtitle ?? '',
          description: banner.description ?? '',
          ctaText: banner.ctaText ?? '',
          ctaLink: banner.ctaLink ?? '',
          ctaNewTab: banner.ctaNewTab,
          ctaSecondaryText: banner.ctaSecondaryText ?? '',
          ctaSecondaryLink: banner.ctaSecondaryLink ?? '',
          textPosition: banner.textPosition,
          textColor: banner.textColor,
          overlayColor: banner.overlayColor ?? '#000000',
          overlayOpacity: String(banner.overlayOpacity),
          active: banner.active,
          sortOrder: String(banner.sortOrder),
        })
        setCurrentImage(banner.image)
        setCurrentMobileImage(banner.mobileImage)
      })
      .catch(() => toast.error('No se pudo cargar el banner'))
      .finally(() => setLoading(false))
  }, [id])

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!isEdit && !image) {
      toast.error('La imagen es obligatoria')
      return
    }
    setSaving(true)
    try {
      const data = new FormData()
      data.append('title', form.title)
      data.append('subtitle', form.subtitle)
      data.append('description', form.description)
      data.append('ctaText', form.ctaText)
      data.append('ctaLink', form.ctaLink)
      data.append('ctaNewTab', String(form.ctaNewTab))
      data.append('ctaSecondaryText', form.ctaSecondaryText)
      data.append('ctaSecondaryLink', form.ctaSecondaryLink)
      data.append('textPosition', form.textPosition)
      data.append('textColor', form.textColor)
      data.append('overlayColor', form.overlayColor)
      data.append('overlayOpacity', form.overlayOpacity || '0')
      data.append('active', String(form.active))
      data.append('sortOrder', form.sortOrder || '0')
      if (image) data.append('newImage', image)
      if (mobileImage) data.append('newMobileImage', mobileImage)

      if (isEdit) {
        await adminApi.banners.update(Number(id), data)
        toast.success('Banner actualizado')
      } else {
        await adminApi.banners.create(data)
        toast.success('Banner creado')
      }
      navigate('/admin/banners')
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudo guardar el banner')
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return <p className="text-muted-foreground">Cargando…</p>
  }

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{isEdit ? 'Editar banner' : 'Nuevo banner'}</h1>

      <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
        <Card>
          <CardHeader>
            <CardTitle>Contenido</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor="title">Título</Label>
              <Input id="title" value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="subtitle">Subtítulo</Label>
              <Input id="subtitle" value={form.subtitle} onChange={(e) => setForm({ ...form, subtitle: e.target.value })} />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="description">Descripción</Label>
              <Textarea
                id="description"
                rows={3}
                value={form.description}
                onChange={(e) => setForm({ ...form, description: e.target.value })}
              />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Botones</CardTitle>
          </CardHeader>
          <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-2">
              <Label htmlFor="ctaText">Texto botón principal</Label>
              <Input id="ctaText" value={form.ctaText} onChange={(e) => setForm({ ...form, ctaText: e.target.value })} />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="ctaLink">Link botón principal</Label>
              <Input id="ctaLink" value={form.ctaLink} onChange={(e) => setForm({ ...form, ctaLink: e.target.value })} />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="ctaSecondaryText">Texto botón secundario</Label>
              <Input
                id="ctaSecondaryText"
                value={form.ctaSecondaryText}
                onChange={(e) => setForm({ ...form, ctaSecondaryText: e.target.value })}
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="ctaSecondaryLink">Link botón secundario</Label>
              <Input
                id="ctaSecondaryLink"
                value={form.ctaSecondaryLink}
                onChange={(e) => setForm({ ...form, ctaSecondaryLink: e.target.value })}
              />
            </div>
            <div className="flex items-center justify-between rounded-md border px-3 py-2 sm:col-span-2">
              <Label htmlFor="ctaNewTab">Abrir en pestaña nueva</Label>
              <Switch
                id="ctaNewTab"
                checked={form.ctaNewTab}
                onCheckedChange={(checked) => setForm({ ...form, ctaNewTab: checked })}
              />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Estilo</CardTitle>
          </CardHeader>
          <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-2">
              <Label>Posición del texto</Label>
              <Select value={form.textPosition} onValueChange={(v) => setForm({ ...form, textPosition: v as FormState['textPosition'] })}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="left">Izquierda</SelectItem>
                  <SelectItem value="center">Centro</SelectItem>
                  <SelectItem value="right">Derecha</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="flex flex-col gap-2">
              <Label>Color del texto</Label>
              <Select value={form.textColor} onValueChange={(v) => setForm({ ...form, textColor: v as FormState['textColor'] })}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="light">Claro</SelectItem>
                  <SelectItem value="dark">Oscuro</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="overlayColor">Color del overlay</Label>
              <Input
                id="overlayColor"
                type="color"
                value={form.overlayColor}
                onChange={(e) => setForm({ ...form, overlayColor: e.target.value })}
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="overlayOpacity">Opacidad del overlay (0-100)</Label>
              <Input
                id="overlayOpacity"
                type="number"
                min="0"
                max="100"
                value={form.overlayOpacity}
                onChange={(e) => setForm({ ...form, overlayOpacity: e.target.value })}
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="sortOrder">Orden</Label>
              <Input id="sortOrder" type="number" value={form.sortOrder} onChange={(e) => setForm({ ...form, sortOrder: e.target.value })} />
            </div>
            <div className="flex items-center justify-between rounded-md border px-3 py-2">
              <Label htmlFor="active">Activo</Label>
              <Switch id="active" checked={form.active} onCheckedChange={(checked) => setForm({ ...form, active: checked })} />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Imágenes</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            {currentImage && (
              <div className="flex items-center gap-3">
                <img src={currentImage} alt="" className="h-16 w-28 rounded object-cover" />
                <span className="text-sm text-muted-foreground">Imagen actual (desktop)</span>
              </div>
            )}
            <div className="flex flex-col gap-2">
              <Label htmlFor="newImage">{currentImage ? 'Reemplazar imagen (desktop)' : 'Imagen (desktop)'}</Label>
              <Input id="newImage" type="file" accept="image/*" onChange={(e) => setImage(e.target.files?.[0] ?? null)} />
            </div>
            {currentMobileImage && (
              <div className="flex items-center gap-3">
                <img src={currentMobileImage} alt="" className="h-16 w-28 rounded object-cover" />
                <span className="text-sm text-muted-foreground">Imagen actual (mobile)</span>
              </div>
            )}
            <div className="flex flex-col gap-2">
              <Label htmlFor="newMobileImage">{currentMobileImage ? 'Reemplazar imagen (mobile)' : 'Imagen (mobile, opcional)'}</Label>
              <Input
                id="newMobileImage"
                type="file"
                accept="image/*"
                onChange={(e) => setMobileImage(e.target.files?.[0] ?? null)}
              />
            </div>
          </CardContent>
        </Card>

        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={() => navigate('/admin/banners')}>
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
