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
import api from '@/services/api'
import type { ShippingZoneDto, ShippingZoneRequest } from '@/admin/types/admin'
import type { GeoRegion } from '@/types/api'

interface FormState {
  name: string
  description: string
  active: boolean
  sortOrder: string
}

// Catálogo de comunas: por región elegida, o la región entera o algunas de sus comunas. Antes eran dos
// cuadros de texto libre, y lo que se escribía tenía que coincidir letra por letra con lo que mandaba el
// checkout; una sola lista de comunas, además, valía para todas las regiones de la zona.
interface RegionSelection {
  whole: boolean
  communes: Set<string>
}

const EMPTY_FORM: FormState = { name: '', description: '', active: true, sortOrder: '0' }

// El backend devuelve en su forma canónica todo lo que reconoce, así que basta con comparar por nombre.
function selectionFrom(zone: ShippingZoneDto, catalog: GeoRegion[]): Map<string, RegionSelection> {
  const selection = new Map<string, RegionSelection>()
  for (const region of catalog) {
    if (!zone.regions.includes(region.name)) continue
    const communes = new Set(region.communes.map((c) => c.name).filter((name) => zone.communes.includes(name)))
    selection.set(region.name, { whole: communes.size === 0, communes })
  }
  return selection
}

export default function ZoneForm() {
  const { id } = useParams()
  const isEdit = Boolean(id)
  const navigate = useNavigate()

  const [catalog, setCatalog] = useState<GeoRegion[]>([])
  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [selection, setSelection] = useState<Map<string, RegionSelection>>(new Map())
  const [unrecognized, setUnrecognized] = useState<string[]>([])
  const [filters, setFilters] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    const zone = id ? adminApi.shippingZones.getById(Number(id)) : Promise.resolve(null)
    Promise.all([api.geo.regions(), zone])
      .then(([regions, loaded]) => {
        setCatalog(regions)
        if (loaded) {
          setForm({
            name: loaded.name,
            description: loaded.description ?? '',
            active: loaded.active,
            sortOrder: String(loaded.sortOrder),
          })
          setSelection(selectionFrom(loaded, regions))
          setUnrecognized(loaded.unrecognized)
        }
      })
      .catch(() => toast.error(id ? 'No se pudo cargar la zona' : 'No se pudo cargar el catálogo de regiones'))
      .finally(() => setLoading(false))
  }, [id])

  function toggleRegion(region: string, checked: boolean) {
    setSelection((prev) => {
      const next = new Map(prev)
      if (checked) next.set(region, { whole: true, communes: new Set() })
      else next.delete(region)
      return next
    })
  }

  function setWhole(region: string, whole: boolean) {
    setSelection((prev) => {
      const current = prev.get(region)
      if (!current) return prev
      return new Map(prev).set(region, { ...current, whole })
    })
  }

  function toggleCommune(region: string, commune: string, checked: boolean) {
    setSelection((prev) => {
      const current = prev.get(region)
      if (!current) return prev
      const communes = new Set(current.communes)
      if (checked) communes.add(commune)
      else communes.delete(commune)
      return new Map(prev).set(region, { ...current, communes })
    })
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    // En el orden del catálogo, de norte a sur, y no en el que se fueron marcando.
    const chosen = catalog.filter((region) => selection.has(region.name))
    if (chosen.length === 0) {
      toast.error('Elige al menos una región')
      return
    }
    const empty = chosen.find((region) => {
      const current = selection.get(region.name)
      return current && !current.whole && current.communes.size === 0
    })
    if (empty) {
      toast.error(`Elige al menos una comuna de ${empty.name}, o marca «Toda la región»`)
      return
    }

    setSaving(true)
    try {
      const request: ShippingZoneRequest = {
        name: form.name,
        description: form.description || null,
        regions: chosen.map((region) => region.name),
        communes: chosen.flatMap((region) => {
          const current = selection.get(region.name)
          if (!current || current.whole) return []
          return region.communes.map((c) => c.name).filter((name) => current.communes.has(name))
        }),
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
            <CardTitle>Cobertura</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            {unrecognized.length > 0 && (
              <p className="rounded-md bg-amber-50 p-3 text-sm text-amber-800">
                Esta zona tiene regiones o comunas escritas a mano que no están en el catálogo, y no se usan
                para cobrar: {unrecognized.join(', ')}. Al guardar se quitan; elige su equivalente en la lista.
              </p>
            )}
            <p className="text-sm text-muted-foreground">
              Si en una región conviven una zona de región entera y otra con comunas elegidas, cada comuna
              elegida paga la tarifa de su zona y el resto de la región, la de la región entera.
            </p>
            <div className="grid gap-2 sm:grid-cols-2">
              {catalog.map((region) => (
                <div key={region.code} className="flex items-center justify-between rounded-md border px-3 py-2">
                  <Label htmlFor={`region-${region.code}`} className="font-normal">
                    {region.name}
                  </Label>
                  <Switch
                    id={`region-${region.code}`}
                    checked={selection.has(region.name)}
                    onCheckedChange={(checked) => toggleRegion(region.name, checked)}
                  />
                </div>
              ))}
            </div>

            {catalog
              .filter((region) => selection.has(region.name))
              .map((region) => {
                const current = selection.get(region.name)!
                const filter = (filters[region.code] ?? '').trim().toLowerCase()
                const visible = region.communes.filter((c) => c.name.toLowerCase().includes(filter))
                return (
                  <div key={region.code} className="flex flex-col gap-3 rounded-md border p-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <p className="text-sm font-medium">{region.name}</p>
                      <div className="flex items-center gap-2">
                        <Label htmlFor={`whole-${region.code}`} className="font-normal">
                          Toda la región
                        </Label>
                        <Switch
                          id={`whole-${region.code}`}
                          aria-label={`Toda la región ${region.name}`}
                          checked={current.whole}
                          onCheckedChange={(checked) => setWhole(region.name, checked)}
                        />
                      </div>
                    </div>
                    {!current.whole && (
                      <>
                        <Input
                          placeholder="Buscar comuna…"
                          aria-label={`Buscar comuna en ${region.name}`}
                          value={filters[region.code] ?? ''}
                          onChange={(event) => setFilters({ ...filters, [region.code]: event.target.value })}
                        />
                        <p className="text-xs text-muted-foreground">{current.communes.size} comuna(s) elegida(s)</p>
                        <div className="grid max-h-64 gap-1 overflow-y-auto sm:grid-cols-2 lg:grid-cols-3">
                          {visible.map((commune) => (
                            <label key={commune.code} className="flex items-center gap-2 text-sm">
                              <input
                                type="checkbox"
                                checked={current.communes.has(commune.name)}
                                onChange={(event) => toggleCommune(region.name, commune.name, event.target.checked)}
                              />
                              {commune.name}
                            </label>
                          ))}
                        </div>
                      </>
                    )}
                  </div>
                )
              })}
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
