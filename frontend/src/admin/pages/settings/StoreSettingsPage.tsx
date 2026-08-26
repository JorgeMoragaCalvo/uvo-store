import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Switch } from '@/components/ui/switch'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { useAdminStoreSettingsStore } from '@/admin/stores/useAdminStoreSettingsStore'
import type { StoreSettingsDto } from '@/admin/types/admin'

function Toggle({ id, label, checked, onChange }: { id: string; label: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <div className="flex items-center justify-between rounded-md border px-3 py-2">
      <Label htmlFor={id}>{label}</Label>
      <Switch id={id} checked={checked} onCheckedChange={onChange} />
    </div>
  )
}

export default function StoreSettingsPage() {
  const settings = useAdminStoreSettingsStore((state) => state.settings)
  const loading = useAdminStoreSettingsStore((state) => state.loading)
  const fetch = useAdminStoreSettingsStore((state) => state.fetch)

  useEffect(() => {
    fetch().catch(() => toast.error('No se pudo cargar la configuración de la tienda'))
  }, [fetch])

  if (loading || !settings) {
    return <p className="text-muted-foreground">Cargando…</p>
  }

  // Keyed by the fact settings just loaded, so local form state initializes fresh instead of
  // needing an effect to resync it whenever the store's `settings` object changes.
  return <StoreSettingsForm key="loaded" initial={settings} />
}

function StoreSettingsForm({ initial }: { initial: StoreSettingsDto }) {
  const update = useAdminStoreSettingsStore((state) => state.update)
  const [form, setForm] = useState<StoreSettingsDto>(initial)
  const [logo, setLogo] = useState<File | null>(null)
  const [favicon, setFavicon] = useState<File | null>(null)
  const [saving, setSaving] = useState(false)

  function set<K extends keyof StoreSettingsDto>(key: K, value: StoreSettingsDto[K]) {
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    try {
      const data = new FormData()
      const append = (key: string, value: string | number | boolean | null) => data.append(key, value === null ? '' : String(value))
      append('storeName', form.storeName)
      append('storeDescription', form.storeDescription)
      append('primaryColor', form.primaryColor)
      append('secondaryColor', form.secondaryColor)
      append('accentColor', form.accentColor)
      append('darkColor', form.darkColor)
      append('showHero', form.showHero)
      append('heroAutoplaySpeed', form.heroAutoplaySpeed)
      append('showCategories', form.showCategories)
      append('categoriesTitle', form.categoriesTitle)
      append('categoriesLimit', form.categoriesLimit)
      append('showNewProducts', form.showNewProducts)
      append('newProductsTitle', form.newProductsTitle)
      append('newProductsLimit', form.newProductsLimit)
      append('newProductsDays', form.newProductsDays)
      append('showFeaturedProducts', form.showFeaturedProducts)
      append('featuredProductsTitle', form.featuredProductsTitle)
      append('featuredProductsLimit', form.featuredProductsLimit)
      append('showDeals', form.showDeals)
      append('dealsTitle', form.dealsTitle)
      append('dealsLimit', form.dealsLimit)
      append('showBenefits', form.showBenefits)
      append('benefit1Icon', form.benefit1Icon)
      append('benefit1Title', form.benefit1Title)
      append('benefit1Description', form.benefit1Description)
      append('benefit2Icon', form.benefit2Icon)
      append('benefit2Title', form.benefit2Title)
      append('benefit2Description', form.benefit2Description)
      append('benefit3Icon', form.benefit3Icon)
      append('benefit3Title', form.benefit3Title)
      append('benefit3Description', form.benefit3Description)
      append('benefit4Icon', form.benefit4Icon)
      append('benefit4Title', form.benefit4Title)
      append('benefit4Description', form.benefit4Description)
      append('contactEmail', form.contactEmail)
      append('contactPhone', form.contactPhone)
      append('whatsappNumber', form.whatsappNumber)
      append('facebookUrl', form.facebookUrl)
      append('instagramUrl', form.instagramUrl)
      append('twitterUrl', form.twitterUrl)
      append('tiktokUrl', form.tiktokUrl)
      append('metaTitle', form.metaTitle)
      append('metaDescription', form.metaDescription)
      append('metaKeywords', form.metaKeywords)
      if (logo) data.append('newLogo', logo)
      if (favicon) data.append('newFavicon', favicon)

      await update(data)
      toast.success('Configuración de tienda guardada')
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudo guardar la configuración')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">Configuración de tienda</h1>

      <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
        <Card>
          <CardHeader>
            <CardTitle>Identidad</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor="storeName">Nombre de la tienda</Label>
              <Input id="storeName" value={form.storeName} onChange={(e) => set('storeName', e.target.value)} />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="storeDescription">Descripción</Label>
              <Textarea
                id="storeDescription"
                rows={3}
                value={form.storeDescription ?? ''}
                onChange={(e) => set('storeDescription', e.target.value)}
              />
            </div>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div className="flex flex-col gap-2">
                {form.storeLogo && <img src={form.storeLogo} alt="Logo actual" className="h-10 w-auto object-contain" />}
                <Label htmlFor="logo">{form.storeLogo ? 'Reemplazar logo' : 'Logo'}</Label>
                <Input id="logo" type="file" accept="image/*" onChange={(e) => setLogo(e.target.files?.[0] ?? null)} />
              </div>
              <div className="flex flex-col gap-2">
                {form.storeFavicon && <img src={form.storeFavicon} alt="Favicon actual" className="size-8 object-contain" />}
                <Label htmlFor="favicon">{form.storeFavicon ? 'Reemplazar favicon' : 'Favicon'}</Label>
                <Input id="favicon" type="file" accept="image/*" onChange={(e) => setFavicon(e.target.files?.[0] ?? null)} />
              </div>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Colores de marca</CardTitle>
          </CardHeader>
          <CardContent className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            {(
              [
                ['primaryColor', 'Primario'],
                ['secondaryColor', 'Secundario'],
                ['accentColor', 'Acento'],
                ['darkColor', 'Oscuro'],
              ] as const
            ).map(([key, label]) => (
              <div key={key} className="flex flex-col gap-2">
                <Label htmlFor={key}>{label}</Label>
                <Input id={key} type="color" value={form[key]} onChange={(e) => set(key, e.target.value)} />
              </div>
            ))}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Secciones del home</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <Toggle id="showHero" label="Mostrar banner principal" checked={form.showHero} onChange={(v) => set('showHero', v)} />

            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              <Toggle id="showCategories" label="Mostrar categorías" checked={form.showCategories} onChange={(v) => set('showCategories', v)} />
              <Input
                placeholder="Título"
                value={form.categoriesTitle ?? ''}
                onChange={(e) => set('categoriesTitle', e.target.value)}
              />
              <Input
                type="number"
                placeholder="Límite"
                value={form.categoriesLimit}
                onChange={(e) => set('categoriesLimit', Number(e.target.value))}
              />
            </div>

            <div className="grid grid-cols-1 gap-4 sm:grid-cols-4">
              <Toggle
                id="showNewProducts"
                label="Mostrar productos nuevos"
                checked={form.showNewProducts}
                onChange={(v) => set('showNewProducts', v)}
              />
              <Input placeholder="Título" value={form.newProductsTitle ?? ''} onChange={(e) => set('newProductsTitle', e.target.value)} />
              <Input
                type="number"
                placeholder="Límite"
                value={form.newProductsLimit}
                onChange={(e) => set('newProductsLimit', Number(e.target.value))}
              />
              <Input
                type="number"
                placeholder="Días"
                value={form.newProductsDays}
                onChange={(e) => set('newProductsDays', Number(e.target.value))}
              />
            </div>

            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              <Toggle
                id="showFeaturedProducts"
                label="Mostrar destacados"
                checked={form.showFeaturedProducts}
                onChange={(v) => set('showFeaturedProducts', v)}
              />
              <Input
                placeholder="Título"
                value={form.featuredProductsTitle ?? ''}
                onChange={(e) => set('featuredProductsTitle', e.target.value)}
              />
              <Input
                type="number"
                placeholder="Límite"
                value={form.featuredProductsLimit}
                onChange={(e) => set('featuredProductsLimit', Number(e.target.value))}
              />
            </div>

            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              <Toggle id="showDeals" label="Mostrar ofertas" checked={form.showDeals} onChange={(v) => set('showDeals', v)} />
              <Input placeholder="Título" value={form.dealsTitle ?? ''} onChange={(e) => set('dealsTitle', e.target.value)} />
              <Input type="number" placeholder="Límite" value={form.dealsLimit} onChange={(e) => set('dealsLimit', Number(e.target.value))} />
            </div>

            <Toggle id="showBenefits" label="Mostrar banda de beneficios" checked={form.showBenefits} onChange={(v) => set('showBenefits', v)} />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Beneficios</CardTitle>
          </CardHeader>
          <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            {([1, 2, 3, 4] as const).map((n) => (
              <div key={n} className="flex flex-col gap-2 rounded-md border p-3">
                <Label>Beneficio {n}</Label>
                <Input
                  placeholder="Ícono (truck, shield-check, refresh, headset)"
                  value={form[`benefit${n}Icon`] ?? ''}
                  onChange={(e) => set(`benefit${n}Icon`, e.target.value)}
                />
                <Input
                  placeholder="Título"
                  value={form[`benefit${n}Title`] ?? ''}
                  onChange={(e) => set(`benefit${n}Title`, e.target.value)}
                />
                <Input
                  placeholder="Descripción"
                  value={form[`benefit${n}Description`] ?? ''}
                  onChange={(e) => set(`benefit${n}Description`, e.target.value)}
                />
              </div>
            ))}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Contacto y redes</CardTitle>
          </CardHeader>
          <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Input placeholder="Email de contacto" value={form.contactEmail ?? ''} onChange={(e) => set('contactEmail', e.target.value)} />
            <Input placeholder="Teléfono de contacto" value={form.contactPhone ?? ''} onChange={(e) => set('contactPhone', e.target.value)} />
            <Input placeholder="WhatsApp" value={form.whatsappNumber ?? ''} onChange={(e) => set('whatsappNumber', e.target.value)} />
            <Input placeholder="Facebook" value={form.facebookUrl ?? ''} onChange={(e) => set('facebookUrl', e.target.value)} />
            <Input placeholder="Instagram" value={form.instagramUrl ?? ''} onChange={(e) => set('instagramUrl', e.target.value)} />
            <Input placeholder="Twitter / X" value={form.twitterUrl ?? ''} onChange={(e) => set('twitterUrl', e.target.value)} />
            <Input placeholder="TikTok" value={form.tiktokUrl ?? ''} onChange={(e) => set('tiktokUrl', e.target.value)} />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>SEO</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <Input placeholder="Meta título" value={form.metaTitle ?? ''} onChange={(e) => set('metaTitle', e.target.value)} />
            <Input placeholder="Meta descripción" value={form.metaDescription ?? ''} onChange={(e) => set('metaDescription', e.target.value)} />
            <Input placeholder="Meta keywords" value={form.metaKeywords ?? ''} onChange={(e) => set('metaKeywords', e.target.value)} />
          </CardContent>
        </Card>

        <div className="flex justify-end">
          <Button type="submit" disabled={saving}>
            {saving ? 'Guardando…' : 'Guardar cambios'}
          </Button>
        </div>
      </form>
    </div>
  )
}
