import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import adminApi from '@/admin/services/adminApi'
import type { ShippingMethodDto, ShippingRateRequest, ShippingRateType, ShippingZoneDto } from '@/admin/types/admin'

interface FormState {
  methodId: string
  zoneId: string
  name: string
  rateType: ShippingRateType
  flatRate: string
  weightRatePerKg: string
  baseWeightRate: string
  minOrderAmount: string
  maxOrderAmount: string
  minWeight: string
  maxWeight: string
  freeShippingThreshold: string
  active: boolean
  sortOrder: string
}

const EMPTY_FORM: FormState = {
  methodId: '',
  zoneId: '',
  name: '',
  rateType: 'FLAT',
  flatRate: '',
  weightRatePerKg: '',
  baseWeightRate: '',
  minOrderAmount: '',
  maxOrderAmount: '',
  minWeight: '',
  maxWeight: '',
  freeShippingThreshold: '',
  active: true,
  sortOrder: '0',
}

function toNumberOrNull(value: string): number | null {
  return value.trim() === '' ? null : Number(value)
}

export default function RateForm() {
  const { id } = useParams()
  const isEdit = Boolean(id)
  const navigate = useNavigate()

  const [methods, setMethods] = useState<ShippingMethodDto[]>([])
  const [zones, setZones] = useState<ShippingZoneDto[]>([])
  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [loading, setLoading] = useState(isEdit)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    adminApi.shippingMethods.list().then(setMethods).catch(() => toast.error('No se pudieron cargar los métodos'))
    adminApi.shippingZones.list().then(setZones).catch(() => toast.error('No se pudieron cargar las zonas'))
  }, [])

  useEffect(() => {
    if (!id) return
    adminApi.shippingRates
      .getById(Number(id))
      .then((rate) => {
        setForm({
          methodId: String(rate.methodId),
          zoneId: String(rate.zoneId),
          name: rate.name,
          rateType: rate.rateType,
          flatRate: rate.flatRate != null ? String(rate.flatRate) : '',
          weightRatePerKg: rate.weightRatePerKg != null ? String(rate.weightRatePerKg) : '',
          baseWeightRate: rate.baseWeightRate != null ? String(rate.baseWeightRate) : '',
          minOrderAmount: rate.minOrderAmount != null ? String(rate.minOrderAmount) : '',
          maxOrderAmount: rate.maxOrderAmount != null ? String(rate.maxOrderAmount) : '',
          minWeight: rate.minWeight != null ? String(rate.minWeight) : '',
          maxWeight: rate.maxWeight != null ? String(rate.maxWeight) : '',
          freeShippingThreshold: rate.freeShippingThreshold != null ? String(rate.freeShippingThreshold) : '',
          active: rate.active,
          sortOrder: String(rate.sortOrder),
        })
      })
      .catch(() => toast.error('No se pudo cargar la tarifa'))
      .finally(() => setLoading(false))
  }, [id])

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    try {
      const request: ShippingRateRequest = {
        methodId: Number(form.methodId),
        zoneId: Number(form.zoneId),
        name: form.name,
        rateType: form.rateType,
        flatRate: toNumberOrNull(form.flatRate),
        weightRatePerKg: toNumberOrNull(form.weightRatePerKg),
        baseWeightRate: toNumberOrNull(form.baseWeightRate),
        minOrderAmount: toNumberOrNull(form.minOrderAmount),
        maxOrderAmount: toNumberOrNull(form.maxOrderAmount),
        minWeight: toNumberOrNull(form.minWeight),
        maxWeight: toNumberOrNull(form.maxWeight),
        freeShippingThreshold: toNumberOrNull(form.freeShippingThreshold),
        active: form.active,
        sortOrder: Number(form.sortOrder) || 0,
      }

      if (isEdit) {
        await adminApi.shippingRates.update(Number(id), request)
        toast.success('Tarifa actualizada')
      } else {
        await adminApi.shippingRates.create(request)
        toast.success('Tarifa creada')
      }
      navigate('/admin/shipping/rates')
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudo guardar la tarifa')
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return <p className="text-muted-foreground">Cargando…</p>
  }

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{isEdit ? 'Editar tarifa' : 'Nueva tarifa'}</h1>

      <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
        <Card>
          <CardHeader>
            <CardTitle>Información general</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="flex flex-col gap-2">
                <Label>Método</Label>
                <Select value={form.methodId || undefined} onValueChange={(value) => setForm({ ...form, methodId: value })}>
                  <SelectTrigger>
                    <SelectValue placeholder="Selecciona un método" />
                  </SelectTrigger>
                  <SelectContent>
                    {methods.map((method) => (
                      <SelectItem key={method.id} value={String(method.id)}>
                        {method.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="flex flex-col gap-2">
                <Label>Zona</Label>
                <Select value={form.zoneId || undefined} onValueChange={(value) => setForm({ ...form, zoneId: value })}>
                  <SelectTrigger>
                    <SelectValue placeholder="Selecciona una zona" />
                  </SelectTrigger>
                  <SelectContent>
                    {zones.map((zone) => (
                      <SelectItem key={zone.id} value={String(zone.id)}>
                        {zone.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="name">Nombre</Label>
              <Input id="name" required value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} />
            </div>
            <div className="flex flex-col gap-2">
              <Label>Tipo de tarifa</Label>
              <Select value={form.rateType} onValueChange={(value) => setForm({ ...form, rateType: value as ShippingRateType })}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="FLAT">Monto fijo</SelectItem>
                  <SelectItem value="WEIGHT_BASED">Por peso</SelectItem>
                  <SelectItem value="PRICE_BASED">Monto fijo (por precio)</SelectItem>
                  <SelectItem value="FREE">Gratis</SelectItem>
                </SelectContent>
              </Select>
            </div>

            {(form.rateType === 'FLAT' || form.rateType === 'PRICE_BASED') && (
              <div className="flex flex-col gap-2">
                <Label htmlFor="flatRate">Monto</Label>
                <Input
                  id="flatRate"
                  type="number"
                  min={0}
                  value={form.flatRate}
                  onChange={(event) => setForm({ ...form, flatRate: event.target.value })}
                />
              </div>
            )}

            {form.rateType === 'WEIGHT_BASED' && (
              <div className="grid gap-4 sm:grid-cols-2">
                <div className="flex flex-col gap-2">
                  <Label htmlFor="baseWeightRate">Costo base</Label>
                  <Input
                    id="baseWeightRate"
                    type="number"
                    min={0}
                    value={form.baseWeightRate}
                    onChange={(event) => setForm({ ...form, baseWeightRate: event.target.value })}
                  />
                </div>
                <div className="flex flex-col gap-2">
                  <Label htmlFor="weightRatePerKg">Costo por kg adicional</Label>
                  <Input
                    id="weightRatePerKg"
                    type="number"
                    min={0}
                    value={form.weightRatePerKg}
                    onChange={(event) => setForm({ ...form, weightRatePerKg: event.target.value })}
                  />
                </div>
              </div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Condiciones</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="flex flex-col gap-2">
                <Label htmlFor="minOrderAmount">Compra mínima</Label>
                <Input
                  id="minOrderAmount"
                  type="number"
                  min={0}
                  value={form.minOrderAmount}
                  onChange={(event) => setForm({ ...form, minOrderAmount: event.target.value })}
                />
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="maxOrderAmount">Compra máxima</Label>
                <Input
                  id="maxOrderAmount"
                  type="number"
                  min={0}
                  value={form.maxOrderAmount}
                  onChange={(event) => setForm({ ...form, maxOrderAmount: event.target.value })}
                />
              </div>
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="flex flex-col gap-2">
                <Label htmlFor="minWeight">Peso mínimo (kg)</Label>
                <Input
                  id="minWeight"
                  type="number"
                  min={0}
                  value={form.minWeight}
                  onChange={(event) => setForm({ ...form, minWeight: event.target.value })}
                />
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="maxWeight">Peso máximo (kg)</Label>
                <Input
                  id="maxWeight"
                  type="number"
                  min={0}
                  value={form.maxWeight}
                  onChange={(event) => setForm({ ...form, maxWeight: event.target.value })}
                />
              </div>
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="freeShippingThreshold">Envío gratis desde</Label>
              <Input
                id="freeShippingThreshold"
                type="number"
                min={0}
                value={form.freeShippingThreshold}
                onChange={(event) => setForm({ ...form, freeShippingThreshold: event.target.value })}
              />
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
          <Button type="button" variant="outline" onClick={() => navigate('/admin/shipping/rates')}>
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
