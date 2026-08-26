import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { Card, CardContent } from '@/components/ui/card'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useAdminGeneralSettingsStore } from '@/admin/stores/useAdminGeneralSettingsStore'
import type { GeneralSettingsDto } from '@/admin/types/admin'

function Field({
  id,
  label,
  value,
  onChange,
  type = 'text',
}: {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  type?: string
}) {
  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id}>{label}</Label>
      <Input id={id} type={type} value={value} onChange={(e) => onChange(e.target.value)} />
    </div>
  )
}

function Toggle({ id, label, checked, onChange }: { id: string; label: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <div className="flex items-center justify-between rounded-md border px-3 py-2">
      <Label htmlFor={id}>{label}</Label>
      <Switch id={id} checked={checked} onCheckedChange={onChange} />
    </div>
  )
}

export default function GeneralSettings() {
  const settings = useAdminGeneralSettingsStore((state) => state.settings)
  const loading = useAdminGeneralSettingsStore((state) => state.loading)
  const fetch = useAdminGeneralSettingsStore((state) => state.fetch)

  useEffect(() => {
    fetch().catch(() => toast.error('No se pudo cargar la configuración'))
  }, [fetch])

  if (loading || !settings) {
    return <p className="text-muted-foreground">Cargando…</p>
  }

  // Keyed by the fact settings just loaded, so local form state initializes fresh instead of
  // needing an effect to resync it whenever the store's `settings` object changes.
  return <GeneralSettingsForm key="loaded" initial={settings} />
}

function GeneralSettingsForm({ initial }: { initial: GeneralSettingsDto }) {
  const update = useAdminGeneralSettingsStore((state) => state.update)
  const [form, setForm] = useState<GeneralSettingsDto>(initial)
  const [saving, setSaving] = useState(false)

  function set<K extends keyof GeneralSettingsDto>(key: K, value: GeneralSettingsDto[K]) {
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    try {
      await update(form)
      toast.success('Configuración guardada')
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudo guardar la configuración')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">Configuración general</h1>

      <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
        <Tabs defaultValue="general">
          <TabsList className="flex-wrap">
            <TabsTrigger value="general">General</TabsTrigger>
            <TabsTrigger value="currency">Moneda</TabsTrigger>
            <TabsTrigger value="shipping">Envío</TabsTrigger>
            <TabsTrigger value="checkout">Checkout</TabsTrigger>
            <TabsTrigger value="stripe">Stripe</TabsTrigger>
            <TabsTrigger value="pos">POS</TabsTrigger>
            <TabsTrigger value="seo">SEO</TabsTrigger>
            <TabsTrigger value="social">Redes</TabsTrigger>
          </TabsList>

          <TabsContent value="general">
            <Card>
              <CardContent className="grid grid-cols-1 gap-4 pt-6 sm:grid-cols-2">
                <Field id="storeName" label="Nombre de la tienda" value={form.storeName} onChange={(v) => set('storeName', v)} />
                <Field id="storeEmail" label="Correo de la tienda" value={form.storeEmail} onChange={(v) => set('storeEmail', v)} />
                <Field id="storePhone" label="Teléfono de la tienda" value={form.storePhone} onChange={(v) => set('storePhone', v)} />
                <Field id="adminEmail" label="Correo de notificaciones" value={form.adminEmail} onChange={(v) => set('adminEmail', v)} />
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="currency">
            <Card>
              <CardContent className="grid grid-cols-1 gap-4 pt-6 sm:grid-cols-2">
                <Field id="currency" label="Moneda" value={form.currency} onChange={(v) => set('currency', v)} />
                <Field id="currencySymbol" label="Símbolo" value={form.currencySymbol} onChange={(v) => set('currencySymbol', v)} />
                <Field id="taxRate" label="Tasa de impuesto (%)" value={form.taxRate} onChange={(v) => set('taxRate', v)} />
                <Toggle
                  id="pricesIncludeTax"
                  label="Los precios ya incluyen impuesto"
                  checked={form.pricesIncludeTax}
                  onChange={(v) => set('pricesIncludeTax', v)}
                />
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="shipping">
            <Card>
              <CardContent className="grid grid-cols-1 gap-4 pt-6 sm:grid-cols-2">
                <Toggle id="shippingEnabled" label="Envío habilitado" checked={form.shippingEnabled} onChange={(v) => set('shippingEnabled', v)} />
                <Field
                  id="defaultShippingCost"
                  label="Costo de envío por defecto"
                  value={form.defaultShippingCost}
                  onChange={(v) => set('defaultShippingCost', v)}
                />
                <Toggle
                  id="freeShippingEnabled"
                  label="Envío gratis habilitado"
                  checked={form.freeShippingEnabled}
                  onChange={(v) => set('freeShippingEnabled', v)}
                />
                <Field
                  id="freeShippingThreshold"
                  label="Monto mínimo para envío gratis"
                  value={form.freeShippingThreshold}
                  onChange={(v) => set('freeShippingThreshold', v)}
                />
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="checkout">
            <Card>
              <CardContent className="grid grid-cols-1 gap-4 pt-6 sm:grid-cols-2">
                <Toggle
                  id="allowGuestCheckout"
                  label="Permitir compra como invitado"
                  checked={form.allowGuestCheckout}
                  onChange={(v) => set('allowGuestCheckout', v)}
                />
                <Toggle id="requirePhone" label="Teléfono obligatorio" checked={form.requirePhone} onChange={(v) => set('requirePhone', v)} />
                <Toggle
                  id="requireCompany"
                  label="Empresa/razón social obligatoria"
                  checked={form.requireCompany}
                  onChange={(v) => set('requireCompany', v)}
                />
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="stripe">
            <Card>
              <CardContent className="grid grid-cols-1 gap-4 pt-6 sm:grid-cols-2">
                <Toggle id="stripeEnabled" label="Stripe habilitado" checked={form.stripeEnabled} onChange={(v) => set('stripeEnabled', v)} />
                <Field id="stripePublicKey" label="Public key" value={form.stripePublicKey} onChange={(v) => set('stripePublicKey', v)} />
                <Field
                  id="stripeSecretKey"
                  label="Secret key"
                  type="password"
                  value={form.stripeSecretKey}
                  onChange={(v) => set('stripeSecretKey', v)}
                />
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="pos">
            <Card>
              <CardContent className="flex flex-col gap-4 pt-6">
                <p className="text-sm text-muted-foreground">
                  Opcional — solo necesario si tu tienda sincroniza stock/productos con UvoPOS.
                </p>
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                  <Toggle
                    id="posSyncEnabled"
                    label="Sincronización con POS habilitada"
                    checked={form.posSyncEnabled}
                    onChange={(v) => set('posSyncEnabled', v)}
                  />
                  <Field id="posApiUrl" label="URL de la API del POS" value={form.posApiUrl} onChange={(v) => set('posApiUrl', v)} />
                  <Field
                    id="posApiToken"
                    label="Token de la API (uvp_&#123;companyId&#125;_&#123;clave&#125;)"
                    value={form.posApiToken}
                    onChange={(v) => set('posApiToken', v)}
                  />
                  <Field
                    id="posWebhookSecret"
                    label="Secreto de webhook"
                    type="password"
                    value={form.posWebhookSecret}
                    onChange={(v) => set('posWebhookSecret', v)}
                  />
                </div>
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="seo">
            <Card>
              <CardContent className="grid grid-cols-1 gap-4 pt-6">
                <Field id="metaTitle" label="Meta título" value={form.metaTitle} onChange={(v) => set('metaTitle', v)} />
                <Field id="metaDescription" label="Meta descripción" value={form.metaDescription} onChange={(v) => set('metaDescription', v)} />
                <Field id="metaKeywords" label="Meta keywords" value={form.metaKeywords} onChange={(v) => set('metaKeywords', v)} />
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="social">
            <Card>
              <CardContent className="grid grid-cols-1 gap-4 pt-6 sm:grid-cols-2">
                <Field id="facebookUrl" label="Facebook" value={form.facebookUrl} onChange={(v) => set('facebookUrl', v)} />
                <Field id="instagramUrl" label="Instagram" value={form.instagramUrl} onChange={(v) => set('instagramUrl', v)} />
                <Field id="twitterUrl" label="Twitter / X" value={form.twitterUrl} onChange={(v) => set('twitterUrl', v)} />
              </CardContent>
            </Card>
          </TabsContent>
        </Tabs>

        <div className="flex justify-end">
          <Button type="submit" disabled={saving}>
            {saving ? 'Guardando…' : 'Guardar cambios'}
          </Button>
        </div>
      </form>
    </div>
  )
}
