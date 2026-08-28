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
import adminApi from '@/admin/services/adminApi'
import type { ShippingZoneRequest } from '@/admin/types/admin'

interface FormState {
  name: string
  description: string
  regions: string
  communes: string
  active: boolean
  sortOrder: string
}

const EMPTY_FORM: FormState = { name: '', description: '', regions: '', communes: '', active: true, sortOrder: '0' }

function linesToArray(value: string): string[] {
  return value
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
}

export default function ZoneForm() {
  const { id } = useParams()
  const isEdit = Boolean(id)
  const navigate = useNavigate()

  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [loading, setLoading] = useState(isEdit)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!id) return
    adminApi.shippingZones
      .getById(Number(id))
      .then((zone) => {
        setForm({
          name: zone.name,
          description: zone.description ?? '',
          regions: zone.regions.join('\n'),
          communes: zone.communes.join('\n'),
          active: zone.active,
          sortOrder: String(zone.sortOrder),
        })
      })
      .catch(() => toast.error('No se pudo cargar la zona'))
      .finally(() => setLoading(false))
  }, [id])

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    try {
      const request: ShippingZoneRequest = {
        name: form.name,
        description: form.description || null,
        regions: linesToArray(form.regions),
        communes: linesToArray(form.communes),
        active: form.active,
        sortOrder: Number(form.sortOrder) || 0,
      }

      if (isEdit) {
        await adminApi.shippingZones.update(Number(id), request)
        toast.success('Zona actualizada')
      } else {
        await adminApi.shippingZones.create(request)
        toast.success('Zona creada')
      }
      navigate('/admin/shipping/zones')
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudo guardar la zona')
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return <p className="text-muted-foreground">Cargando…</p>
  }

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{isEdit ? 'Editar zona' : 'Nueva zona'}</h1>

      <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
        <Card>
          <CardHeader>
            <CardTitle>Información general</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor="name">Nombre</Label>
              <Input id="name" required value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="description">Descripción</Label>
              <Textarea
                id="description"
                rows={2}
                value={form.description}
                onChange={(event) => setForm({ ...form, description: event.target.value })}
              />
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="flex flex-col gap-2">
                <Label htmlFor="regions">Regiones (una por línea)</Label>
                <Textarea
                  id="regions"
                  rows={5}
                  placeholder={'Región Metropolitana\nValparaíso'}
                  value={form.regions}
                  onChange={(event) => setForm({ ...form, regions: event.target.value })}
                />
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="communes">Comunas (una por línea, opcional)</Label>
                <Textarea
                  id="communes"
                  rows={5}
                  placeholder="Vacío = toda la región"
                  value={form.communes}
                  onChange={(event) => setForm({ ...form, communes: event.target.value })}
                />
              </div>
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

        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={() => navigate('/admin/shipping/zones')}>
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
