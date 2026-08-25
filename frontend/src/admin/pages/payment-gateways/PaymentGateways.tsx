import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { useAdminPaymentGatewaysStore } from '@/admin/stores/useAdminPaymentGatewaysStore'
import type { PaymentGateway, PaymentGatewayConfigDto } from '@/admin/types/admin'

interface CredentialField {
  key: string
  label: string
  type?: string
}

interface GatewayDefinition {
  gateway: PaymentGateway
  title: string
  description: string
  fields: CredentialField[]
}

const GATEWAYS: GatewayDefinition[] = [
  {
    gateway: 'WEBPAY',
    title: 'Webpay Plus (Transbank)',
    description:
      'El código de comercio hijo que Transbank te asignó al afiliar tu negocio bajo el Mall de la plataforma.',
    fields: [{ key: 'childCommerceCode', label: 'Código de comercio (childCommerceCode)' }],
  },
  {
    gateway: 'MERCADOPAGO',
    title: 'MercadoPago',
    description: 'Credenciales de tu propia cuenta de MercadoPago (Checkout Pro).',
    fields: [
      { key: 'accessToken', label: 'Access Token', type: 'password' },
      { key: 'publicKey', label: 'Public Key' },
    ],
  },
]

function GatewayCard({ definition, config }: { definition: GatewayDefinition; config: PaymentGatewayConfigDto | undefined }) {
  const update = useAdminPaymentGatewaysStore((state) => state.update)
  const [enabled, setEnabled] = useState(config?.enabled ?? false)
  const [values, setValues] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)

  async function handleSave() {
    setSaving(true)
    try {
      const credentials = Object.fromEntries(Object.entries(values).filter(([, v]) => v.trim() !== ''))
      await update(definition.gateway, enabled, credentials)
      setValues({})
      toast.success(`${definition.title} actualizado`)
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudo guardar la configuración')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle>{definition.title}</CardTitle>
            <CardDescription>{definition.description}</CardDescription>
          </div>
          <div className="flex items-center gap-2">
            <Label htmlFor={`${definition.gateway}-enabled`} className="text-sm text-muted-foreground">
              Habilitado
            </Label>
            <Switch id={`${definition.gateway}-enabled`} checked={enabled} onCheckedChange={setEnabled} />
          </div>
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {definition.fields.map((field) => {
          const isSet = config?.credentialsSet?.[field.key]
          return (
            <div key={field.key} className="flex flex-col gap-2">
              <Label htmlFor={`${definition.gateway}-${field.key}`}>
                {field.label} {isSet && <span className="text-xs text-muted-foreground">(configurado)</span>}
              </Label>
              <Input
                id={`${definition.gateway}-${field.key}`}
                type={field.type ?? 'text'}
                placeholder={isSet ? '•••••••• (dejar en blanco para no cambiar)' : ''}
                value={values[field.key] ?? ''}
                onChange={(event) => setValues({ ...values, [field.key]: event.target.value })}
              />
            </div>
          )
        })}
        <div className="flex justify-end">
          <Button onClick={handleSave} disabled={saving}>
            {saving ? 'Guardando…' : 'Guardar'}
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}

export default function PaymentGateways() {
  const configs = useAdminPaymentGatewaysStore((state) => state.configs)
  const loading = useAdminPaymentGatewaysStore((state) => state.loading)
  const fetch = useAdminPaymentGatewaysStore((state) => state.fetch)

  useEffect(() => {
    fetch().catch(() => toast.error('No se pudieron cargar las pasarelas de pago'))
  }, [fetch])

  return (
    <div className="flex flex-col gap-4">
      <div>
        <h1 className="text-2xl font-semibold">Pasarelas de pago</h1>
        <p className="text-sm text-muted-foreground">
          Stripe se configura a nivel de plataforma (variables de entorno) y no requiere credenciales acá.
        </p>
      </div>

      {loading ? (
        <p className="text-muted-foreground">Cargando…</p>
      ) : (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          {GATEWAYS.map((definition) => (
            <GatewayCard
              key={definition.gateway}
              definition={definition}
              config={configs.find((c) => c.gateway === definition.gateway)}
            />
          ))}
        </div>
      )}
    </div>
  )
}
