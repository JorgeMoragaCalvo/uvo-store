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
import type { CouponRequest, CouponType } from '@/admin/types/admin'

interface FormState {
  code: string
  name: string
  description: string
  type: CouponType
  value: string
  minimumPurchase: string
  maximumDiscount: string
  startsAt: string
  expiresAt: string
  usageLimit: string
  usageLimitPerCustomer: string
  active: boolean
}

const EMPTY_FORM: FormState = {
  code: '',
  name: '',
  description: '',
  type: 'percentage',
  value: '',
  minimumPurchase: '',
  maximumDiscount: '',
  startsAt: '',
  expiresAt: '',
  usageLimit: '',
  usageLimitPerCustomer: '',
  active: true,
}

// datetime-local inputs use local "yyyy-MM-ddTHH:mm" with no timezone — convert to/from ISO instant.
function toDateTimeLocal(iso: string | null): string {
  if (!iso) return ''
  const date = new Date(iso)
  const offset = date.getTimezoneOffset() * 60000
  return new Date(date.getTime() - offset).toISOString().slice(0, 16)
}

function toIso(dateTimeLocal: string): string | null {
  return dateTimeLocal ? new Date(dateTimeLocal).toISOString() : null
}

function toNumberOrNull(value: string): number | null {
  return value.trim() === '' ? null : Number(value)
}

export default function CouponForm() {
  const { id } = useParams()
  const isEdit = Boolean(id)
  const navigate = useNavigate()

  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [loading, setLoading] = useState(isEdit)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!id) return
    adminApi.coupons
      .getById(Number(id))
      .then((coupon) => {
        setForm({
          code: coupon.code,
          name: coupon.name,
          description: coupon.description ?? '',
          type: coupon.type,
          value: String(coupon.value),
          minimumPurchase: coupon.minimumPurchase != null ? String(coupon.minimumPurchase) : '',
          maximumDiscount: coupon.maximumDiscount != null ? String(coupon.maximumDiscount) : '',
          startsAt: toDateTimeLocal(coupon.startsAt),
          expiresAt: toDateTimeLocal(coupon.expiresAt),
          usageLimit: coupon.usageLimit != null ? String(coupon.usageLimit) : '',
          usageLimitPerCustomer: coupon.usageLimitPerCustomer != null ? String(coupon.usageLimitPerCustomer) : '',
          active: coupon.active,
        })
      })
      .catch(() => toast.error('No se pudo cargar el cupón'))
      .finally(() => setLoading(false))
  }, [id])

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    try {
      const request: CouponRequest = {
        code: form.code.toUpperCase(),
        name: form.name,
        description: form.description || null,
        type: form.type,
        value: Number(form.value),
        minimumPurchase: toNumberOrNull(form.minimumPurchase),
        maximumDiscount: toNumberOrNull(form.maximumDiscount),
        startsAt: toIso(form.startsAt),
        expiresAt: toIso(form.expiresAt),
        usageLimit: toNumberOrNull(form.usageLimit),
        usageLimitPerCustomer: toNumberOrNull(form.usageLimitPerCustomer),
        active: form.active,
      }

      if (isEdit) {
        await adminApi.coupons.update(Number(id), request)
        toast.success('Cupón actualizado')
      } else {
        await adminApi.coupons.create(request)
        toast.success('Cupón creado')
      }
      navigate('/admin/coupons')
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudo guardar el cupón')
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return <p className="text-muted-foreground">Cargando…</p>
  }

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{isEdit ? 'Editar cupón' : 'Nuevo cupón'}</h1>

      <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
        <Card>
          <CardHeader>
            <CardTitle>Información general</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="flex flex-col gap-2">
                <Label htmlFor="code">Código</Label>
                <Input
                  id="code"
                  required
                  value={form.code}
                  onChange={(event) => setForm({ ...form, code: event.target.value.toUpperCase() })}
                />
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="name">Nombre</Label>
                <Input id="name" required value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} />
              </div>
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
            <div className="flex items-center justify-between rounded-md border px-3 py-2">
              <Label htmlFor="active">Activo</Label>
              <Switch id="active" checked={form.active} onCheckedChange={(checked) => setForm({ ...form, active: checked })} />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Descuento</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="flex flex-col gap-2">
                <Label>Tipo</Label>
                <Select value={form.type} onValueChange={(value) => setForm({ ...form, type: value as CouponType })}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="percentage">Porcentaje</SelectItem>
                    <SelectItem value="fixed">Monto fijo</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="value">{form.type === 'percentage' ? 'Porcentaje (%)' : 'Monto ($)'}</Label>
                <Input
                  id="value"
                  type="number"
                  min={0}
                  max={form.type === 'percentage' ? 100 : undefined}
                  required
                  value={form.value}
                  onChange={(event) => setForm({ ...form, value: event.target.value })}
                />
              </div>
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="flex flex-col gap-2">
                <Label htmlFor="minimumPurchase">Compra mínima</Label>
                <Input
                  id="minimumPurchase"
                  type="number"
                  min={0}
                  value={form.minimumPurchase}
                  onChange={(event) => setForm({ ...form, minimumPurchase: event.target.value })}
                />
              </div>
              {form.type === 'percentage' && (
                <div className="flex flex-col gap-2">
                  <Label htmlFor="maximumDiscount">Descuento máximo</Label>
                  <Input
                    id="maximumDiscount"
                    type="number"
                    min={0}
                    value={form.maximumDiscount}
                    onChange={(event) => setForm({ ...form, maximumDiscount: event.target.value })}
                  />
                </div>
              )}
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Vigencia y límites de uso</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="flex flex-col gap-2">
                <Label htmlFor="startsAt">Desde</Label>
                <Input
                  id="startsAt"
                  type="datetime-local"
                  value={form.startsAt}
                  onChange={(event) => setForm({ ...form, startsAt: event.target.value })}
                />
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="expiresAt">Hasta</Label>
                <Input
                  id="expiresAt"
                  type="datetime-local"
                  value={form.expiresAt}
                  onChange={(event) => setForm({ ...form, expiresAt: event.target.value })}
                />
              </div>
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="flex flex-col gap-2">
                <Label htmlFor="usageLimit">Límite de uso total</Label>
                <Input
                  id="usageLimit"
                  type="number"
                  min={0}
                  value={form.usageLimit}
                  onChange={(event) => setForm({ ...form, usageLimit: event.target.value })}
                />
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="usageLimitPerCustomer">Límite por cliente</Label>
                <Input
                  id="usageLimitPerCustomer"
                  type="number"
                  min={0}
                  value={form.usageLimitPerCustomer}
                  onChange={(event) => setForm({ ...form, usageLimitPerCustomer: event.target.value })}
                />
              </div>
            </div>
          </CardContent>
        </Card>

        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={() => navigate('/admin/coupons')}>
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
