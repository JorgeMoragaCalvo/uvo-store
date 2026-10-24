import { useState } from 'react'
import type { FormEvent } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import platformApi, { type StoreOnboardingResponse } from '@/platform/services/platformApi'

// POS-6. Sin contraseña del cliente: la tienda nace con su dueño invitado por correo, y la contraseña la
// elige él. Antes la escribía el operador, que la conocía para siempre.
interface FormState {
  platformKey: string
  slug: string
  domain: string
  storeName: string
  adminName: string
  adminEmail: string
}

const EMPTY_FORM: FormState = {
  platformKey: '',
  slug: '',
  domain: '',
  storeName: '',
  adminName: '',
  adminEmail: '',
}

export default function NewStore() {
  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [saving, setSaving] = useState(false)
  const [created, setCreated] = useState<StoreOnboardingResponse | null>(null)

  function set<K extends keyof FormState>(key: K, value: string) {
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    try {
      const response = await platformApi.createStore(form.platformKey, {
        slug: form.slug,
        domain: form.domain,
        storeName: form.storeName,
        adminName: form.adminName,
        adminEmail: form.adminEmail,
      })
      setCreated(response)
      toast.success('Tienda creada')
    } catch (err) {
      const { status, message } = (err ?? {}) as { status?: number; message?: string }
      // POS-6: fuera de la red privada la API de plataforma responde 404, como si no existiera.
      if (status === 404) {
        toast.error('La API de plataforma no es accesible desde esta red')
      } else {
        toast.error(message ?? 'No se pudo crear la tienda')
      }
    } finally {
      setSaving(false)
    }
  }

  if (created) {
    return (
      <div className="mx-auto flex min-h-svh max-w-lg items-center justify-center p-4">
        <Card className="w-full">
          <CardHeader>
            <CardTitle>Tienda creada</CardTitle>
            <CardDescription>Pásale estos datos al cliente para que entre a su panel.</CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3 text-sm">
            <p>
              Le enviamos una invitación a <span className="font-medium">{created.adminEmail}</span> para que
              elija su contraseña. Si no le llega, puede usar «¿Olvidaste tu contraseña?» en el panel.
            </p>
            <div>
              <span className="text-muted-foreground">Tienda: </span>
              <span className="font-medium">{created.storeName}</span>
            </div>
            <div>
              <span className="text-muted-foreground">Nick (subdominio interno): </span>
              <span className="font-medium">{created.slug}</span>
            </div>
            {created.domain && (
              <div>
                <span className="text-muted-foreground">Dominio propio: </span>
                <span className="font-medium">{created.domain}</span>
                {!created.domainVerifiedAt && (
                  <span className="text-muted-foreground"> — sin verificar todavía</span>
                )}
              </div>
            )}
            <div>
              <span className="text-muted-foreground">Usuario: </span>
              <span className="font-medium">{created.adminEmail}</span>
            </div>
            {/* PROD-04: las direcciones concretas, compuestas por el backend. Antes había que armarlas
                de memoria a partir del nick. */}
            <div>
              <span className="text-muted-foreground">Tienda: </span>
              <span className="font-medium break-all">{created.storefrontUrl}</span>
            </div>
            <div>
              <span className="text-muted-foreground">Panel: </span>
              <span className="font-medium break-all">{created.adminUrl}</span>
            </div>
            {created.domain && !created.domainVerifiedAt && (
              <p className="text-xs text-muted-foreground">
                Mientras el dominio propio no esté verificado, estas direcciones y los enlaces de los
                correos usan el subdominio de la plataforma, que funciona siempre.
              </p>
            )}
            <Button className="mt-2" onClick={() => setCreated(null)}>
              Crear otra tienda
            </Button>
          </CardContent>
        </Card>
      </div>
    )
  }

  return (
    <div className="mx-auto flex min-h-svh max-w-lg items-center justify-center p-4">
      <Card className="w-full">
        <CardHeader>
          <CardTitle>Nueva tienda</CardTitle>
          <CardDescription>Herramienta interna — carga los datos que te envió el cliente.</CardDescription>
        </CardHeader>
        <CardContent>
          <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
            <div className="flex flex-col gap-2">
              <Label htmlFor="platformKey">API key de plataforma</Label>
              <Input
                id="platformKey"
                type="password"
                required
                value={form.platformKey}
                onChange={(e) => set('platformKey', e.target.value)}
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="slug">Nick (subdominio interno)</Label>
              <Input
                id="slug"
                required
                placeholder="tiendadejuan"
                value={form.slug}
                onChange={(e) => set('slug', e.target.value.toLowerCase())}
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="domain">Dominio propio del cliente (opcional)</Label>
              <Input
                id="domain"
                placeholder="tiendadejuan.cl"
                value={form.domain}
                onChange={(e) => set('domain', e.target.value.toLowerCase())}
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="storeName">Nombre de la tienda</Label>
              <Input id="storeName" required value={form.storeName} onChange={(e) => set('storeName', e.target.value)} />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="adminName">Nombre del cliente</Label>
              <Input id="adminName" required value={form.adminName} onChange={(e) => set('adminName', e.target.value)} />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="adminEmail">Email del cliente</Label>
              <Input
                id="adminEmail"
                type="email"
                required
                value={form.adminEmail}
                onChange={(e) => set('adminEmail', e.target.value)}
              />
            </div>
            <p className="text-xs text-muted-foreground">
              El cliente recibirá una invitación en ese correo para elegir su contraseña.
            </p>
            <Button type="submit" disabled={saving}>
              {saving ? 'Creando…' : 'Crear tienda'}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
