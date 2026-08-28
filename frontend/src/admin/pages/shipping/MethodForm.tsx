import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Switch } from '@/components/ui/switch'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import adminApi from '@/admin/services/adminApi'
import type { ShippingCarrier, ShippingMethodDto, ShippingMethodKind, ShippingMethodRequest } from '@/admin/types/admin'

interface FormState {
  name: string
  code: string
  description: string
  type: ShippingMethodKind
  hasApiIntegration: boolean
  carrier: ShippingCarrier | ''
  minDeliveryDays: string
  maxDeliveryDays: string
  active: boolean
  sortOrder: string
}

const EMPTY_FORM: FormState = {
  name: '',
  code: '',
  description: '',
  type: 'COURIER',
  hasApiIntegration: false,
  carrier: '',
  minDeliveryDays: '',
  maxDeliveryDays: '',
  active: true,
  sortOrder: '0',
}

const CREDENTIAL_FIELDS: Record<string, { key: string; label: string }[]> = {
  CHILEXPRESS: [
    { key: 'subscriptionKey', label: 'Subscription Key' },
    { key: 'originCountyCode', label: 'Código de comuna de origen' },
  ],
  CORREOS_CHILE: [],
}

function toNumberOrNull(value: string): number | null {
  return value.trim() === '' ? null : Number(value)
}

export default function MethodForm() {
  const { id } = useParams()
  const isEdit = Boolean(id)
  const navigate = useNavigate()

  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [credentialsSet, setCredentialsSet] = useState<Record<string, boolean>>({})
  const [credentialInputs, setCredentialInputs] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(isEdit)
  const [saving, setSaving] = useState(false)
  const [savingCredentials, setSavingCredentials] = useState(false)

  useEffect(() => {
    if (!id) return
    adminApi.shippingMethods
      .getById(Number(id))
      .then((method: ShippingMethodDto) => {
        setForm({
          name: method.name,
          code: method.code,
          description: method.description ?? '',
          type: method.type,
          hasApiIntegration: method.hasApiIntegration,
          carrier: method.carrier ?? '',
          minDeliveryDays: method.minDeliveryDays != null ? String(method.minDeliveryDays) : '',
          maxDeliveryDays: method.maxDeliveryDays != null ? String(method.maxDeliveryDays) : '',
          active: method.active,
          sortOrder: String(method.sortOrder),
        })
        setCredentialsSet(method.credentialsSet)
      })
      .catch(() => toast.error('No se pudo cargar el método'))
      .finally(() => setLoading(false))
  }, [id])

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    try {
      const request: ShippingMethodRequest = {
        name: form.name,
        code: form.code,
        description: form.description || null,
        type: form.type,
        hasApiIntegration: form.hasApiIntegration,
        carrier: form.hasApiIntegration && form.carrier ? form.carrier : null,
        minDeliveryDays: toNumberOrNull(form.minDeliveryDays),
        maxDeliveryDays: toNumberOrNull(form.maxDeliveryDays),
        active: form.active,
        sortOrder: Number(form.sortOrder) || 0,
      }

      if (isEdit) {
        await adminApi.shippingMethods.update(Number(id), request)
        toast.success('Método actualizado')
      } else {
        await adminApi.shippingMethods.create(request)
        toast.success('Método creado')
      }
      navigate('/admin/shipping/methods')
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudo guardar el método')
    } finally {
      setSaving(false)
    }
  }

  async function handleSaveCredentials() {
    if (!id) return
    setSavingCredentials(true)
    try {
      const updated = await adminApi.shippingMethods.updateCredentials(Number(id), credentialInputs)
      setCredentialsSet(updated.credentialsSet)
      setCredentialInputs({})
      toast.success('Credenciales guardadas')
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudieron guardar las credenciales')
    } finally {
      setSavingCredentials(false)
    }
  }

  if (loading) {
    return <p className="text-muted-foreground">Cargando…</p>
  }

  const credentialFields = form.carrier ? (CREDENTIAL_FIELDS[form.carrier] ?? []) : []

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{isEdit ? 'Editar método' : 'Nuevo método'}</h1>

      <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
        <Card>
          <CardHeader>
            <CardTitle>Información general</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="flex flex-col gap-2">
                <Label htmlFor="name">Nombre</Label>
                <Input id="name" required value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} />
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="code">Código</Label>
                <Input id="code" required value={form.code} onChange={(event) => setForm({ ...form, code: event.target.value })} />
              </div>
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
            <div className="grid gap-4 sm:grid-cols-3">
              <div className="flex flex-col gap-2">
                <Label>Tipo</Label>
                <Select value={form.type} onValueChange={(value) => setForm({ ...form, type: value as ShippingMethodKind })}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="COURIER">Courier</SelectItem>
                    <SelectItem value="PICKUP">Retiro en tienda</SelectItem>
                    <SelectItem value="CUSTOM">Personalizado</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="minDeliveryDays">Días mín. de entrega</Label>
                <Input
                  id="minDeliveryDays"
                  type="number"
                  min={0}
                  value={form.minDeliveryDays}
                  onChange={(event) => setForm({ ...form, minDeliveryDays: event.target.value })}
                />
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="maxDeliveryDays">Días máx. de entrega</Label>
                <Input
                  id="maxDeliveryDays"
                  type="number"
                  min={0}
                  value={form.maxDeliveryDays}
                  onChange={(event) => setForm({ ...form, maxDeliveryDays: event.target.value })}
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
              <Label htmlFor="active">Activo</Label>
              <Switch id="active" checked={form.active} onCheckedChange={(checked) => setForm({ ...form, active: checked })} />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Integración con transportista</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <div className="flex items-center justify-between rounded-md border px-3 py-2">
              <Label htmlFor="hasApiIntegration">Cotizar automáticamente con un transportista</Label>
              <Switch
                id="hasApiIntegration"
                checked={form.hasApiIntegration}
                onCheckedChange={(checked) => setForm({ ...form, hasApiIntegration: checked })}
              />
            </div>
            {form.hasApiIntegration && (
              <div className="flex flex-col gap-2">
                <Label>Transportista</Label>
                <Select
                  value={form.carrier || undefined}
                  onValueChange={(value) => setForm({ ...form, carrier: value as ShippingCarrier })}
                >
                  <SelectTrigger>
                    <SelectValue placeholder="Selecciona un transportista" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="CHILEXPRESS">Chilexpress</SelectItem>
                    <SelectItem value="CORREOS_CHILE">Correos de Chile</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            )}
          </CardContent>
        </Card>

        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={() => navigate('/admin/shipping/methods')}>
            Cancelar
          </Button>
          <Button type="submit" disabled={saving}>
            {saving ? 'Guardando…' : 'Guardar'}
          </Button>
        </div>
      </form>

      {isEdit && form.hasApiIntegration && form.carrier && credentialFields.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Credenciales de {form.carrier}</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            {credentialFields.map((field) => (
              <div key={field.key} className="flex flex-col gap-2">
                <Label htmlFor={`cred-${field.key}`}>
                  {field.label}{' '}
                  {credentialsSet[field.key] && (
                    <Badge variant="outline" className="ml-2">
                      Configurada
                    </Badge>
                  )}
                </Label>
                <Input
                  id={`cred-${field.key}`}
                  type="password"
                  placeholder={credentialsSet[field.key] ? '••••••••' : ''}
                  value={credentialInputs[field.key] ?? ''}
                  onChange={(event) => setCredentialInputs({ ...credentialInputs, [field.key]: event.target.value })}
                />
              </div>
            ))}
            <div className="flex justify-end">
              <Button type="button" disabled={savingCredentials} onClick={handleSaveCredentials}>
                {savingCredentials ? 'Guardando…' : 'Guardar credenciales'}
              </Button>
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
