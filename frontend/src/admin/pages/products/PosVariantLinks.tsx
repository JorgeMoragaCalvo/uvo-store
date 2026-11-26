import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import adminApi from '@/admin/services/adminApi'
import type { PosVariantLink, ProductVariationDto } from '@/admin/types/admin'

// IMP-08. El contrato con UvoPOS (§4): el POS no tiene variantes, cada una es un Producto suyo, y la agrupación es de
// la tienda. Aquí se escribe a mano qué Producto del POS es cada variante (decisión del usuario). Una vez vinculada,
// su SKU, su precio y su stock son del POS: el backend no deja cambiarlos y la venta manda ese id.
export default function PosVariantLinks({
  productId,
  variations,
}: {
  productId: number
  variations: ProductVariationDto[]
}) {
  const [links, setLinks] = useState<Record<number, number>>({})
  const [drafts, setDrafts] = useState<Record<number, string>>({})
  const [busy, setBusy] = useState<number | null>(null)

  useEffect(() => {
    adminApi.pos
      .variantLinks(productId)
      .then((list: PosVariantLink[]) => {
        const byVariation: Record<number, number> = {}
        list.forEach((link) => {
          byVariation[link.variationId] = link.posProductId
        })
        setLinks(byVariation)
      })
      .catch(() => toast.error('No se pudo leer el vínculo de las variantes con UvoPOS'))
  }, [productId])

  async function link(variationId: number) {
    const value = Number((drafts[variationId] ?? '').trim())
    if (!Number.isInteger(value) || value <= 0) {
      toast.error('Escribe el id numérico del Producto del POS')
      return
    }
    setBusy(variationId)
    try {
      const saved = await adminApi.pos.linkVariant(productId, variationId, value)
      setLinks((prev) => ({ ...prev, [variationId]: saved.posProductId }))
      setDrafts((prev) => ({ ...prev, [variationId]: '' }))
      toast.success('Variante vinculada a UvoPOS')
    } catch (err) {
      toast.error((err as { message?: string })?.message ?? 'No se pudo vincular la variante')
    } finally {
      setBusy(null)
    }
  }

  async function unlink(variationId: number) {
    setBusy(variationId)
    try {
      await adminApi.pos.unlinkVariant(productId, variationId)
      setLinks((prev) => {
        const next = { ...prev }
        delete next[variationId]
        return next
      })
      toast.success('Variante desvinculada')
    } catch (err) {
      toast.error((err as { message?: string })?.message ?? 'No se pudo desvincular la variante')
    } finally {
      setBusy(null)
    }
  }

  if (variations.length === 0) return null

  return (
    <Card>
      <CardContent className="flex flex-col gap-4 pt-6">
        <div className="flex flex-col gap-1">
          <p className="text-sm font-medium">Variantes en UvoPOS</p>
          <p className="text-sm text-muted-foreground">
            En el POS cada variante es un producto aparte. Escribe su id para vincularla: desde ahí su SKU, su precio
            y su stock los gestiona el POS.
          </p>
        </div>
        <ul className="flex flex-col gap-3">
          {variations.map((variation) => {
            const linked = links[variation.id]
            const label = Object.values(variation.attributes ?? {}).join(' / ') || variation.sku
            const inputId = `pos-link-${variation.id}`
            return (
              <li key={variation.id} className="flex flex-col gap-2 rounded-md border p-3 sm:flex-row sm:items-end">
                <div className="flex flex-1 flex-col gap-1">
                  <Label htmlFor={inputId}>
                    {label} <span className="text-xs text-muted-foreground">({variation.sku})</span>
                  </Label>
                  {linked ? (
                    <p className="text-sm">Producto del POS {linked}</p>
                  ) : (
                    <Input
                      id={inputId}
                      inputMode="numeric"
                      placeholder="Id del Producto del POS"
                      value={drafts[variation.id] ?? ''}
                      onChange={(e) => setDrafts((prev) => ({ ...prev, [variation.id]: e.target.value }))}
                    />
                  )}
                </div>
                {linked ? (
                  <Button type="button" variant="outline" disabled={busy === variation.id}
                    onClick={() => unlink(variation.id)}>
                    Desvincular
                  </Button>
                ) : (
                  <Button type="button" disabled={busy === variation.id} onClick={() => link(variation.id)}>
                    Vincular
                  </Button>
                )}
              </li>
            )
          })}
        </ul>
      </CardContent>
    </Card>
  )
}
