import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import LegalLayout from '../../components/legal/LegalLayout'
import type { LegalSection } from '../../components/legal/LegalLayout'
import api from '../../services/api'
import { useStoreSettingsStore } from '../../stores/useStoreSettingsStore'
import type { PublicShippingMethod, ShippingCoverage } from '../../types/api'
import { formatCurrency } from '../../utils/currency'

// GAP-18. Esta página anunciaba tarifas («desde $5.990»), zonas, plazos y un envío gratis sobre $50.000 escritos
// a mano, iguales para todas las tiendas, y un correo con el número de seguimiento que nadie envía. Ahora sale
// de lo que la tienda configuró: su cobertura, los métodos que el checkout de verdad ofrece y su envío gratis.
// Lo que no varía entre tiendas está en docs/revision-legal-paginas.md, a la espera de la revisión legal.

type ShippingData = { coverage: ShippingCoverage[]; methods: PublicShippingMethod[] }

// Mismo texto que ShippingRateServiceImpl.deliveryTimeString, que es el que el comprador ve en el checkout.
function deliveryTime(method: PublicShippingMethod): string | null {
  const { minDeliveryDays: min, maxDeliveryDays: max } = method
  if (min != null && max != null) {
    if (min !== max) return `${min}-${max} días hábiles`
    return min === 1 ? '1 día hábil' : `${min} días hábiles`
  }
  if (min != null) return `${min}+ días hábiles`
  if (max != null) return `hasta ${max} días hábiles`
  return null
}

export default function ShippingPolicy() {
  const settings = useStoreSettingsStore((state) => state.settings)
  const [data, setData] = useState<ShippingData | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let cancelled = false
    Promise.all([api.shipping.coverage(), api.shipping.methods()])
      .then(([coverage, methods]) => {
        if (!cancelled) setData({ coverage, methods })
      })
      .catch(() => {
        if (!cancelled) setFailed(true)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const freeShippingThreshold = Number(settings?.freeShippingThreshold)
  const freeShipping = settings?.freeShippingEnabled && freeShippingThreshold > 0 ? freeShippingThreshold : null

  return (
    <LegalLayout
      title="Política de Envíos"
      intro="Dónde despachamos, con qué métodos, en qué plazos y cómo se calcula el costo."
      sections={settings && !settings.shippingEnabled ? withoutShipping() : withShipping(data, failed, freeShipping)}
    />
  )
}

function withoutShipping(): LegalSection[] {
  return [
    {
      title: '1. Entrega',
      body: <p>Esta tienda no cobra el envío en el checkout. Consúltenos por los datos de contacto de abajo cómo y cuándo se entrega su pedido.</p>,
    },
    trackingSection('2'),
  ]
}

function withShipping(data: ShippingData | null, failed: boolean, freeShipping: number | null): LegalSection[] {
  if (failed) {
    return [
      {
        title: 'Envíos',
        body: <p>No pudimos cargar la información de envíos de la tienda. Intente de nuevo en unos minutos; el costo y el plazo exactos siempre se muestran en el checkout antes de pagar.</p>,
      },
    ]
  }
  if (!data) {
    return [{ title: 'Envíos', body: <p>Cargando la información de envíos…</p> }]
  }

  return [
    {
      title: '1. Cobertura',
      body:
        data.coverage.length === 0 ? (
          <p>La tienda aún no tiene zonas de despacho configuradas.</p>
        ) : (
          <>
            <p>Despachamos a las siguientes regiones. Las comunas disponibles se eligen en el checkout:</p>
            <ul className="list-inside list-disc space-y-1">
              {data.coverage.map((zone) => (
                <li key={zone.region}>{zone.region}</li>
              ))}
            </ul>
          </>
        ),
    },
    {
      title: '2. Métodos y plazos',
      body:
        data.methods.length === 0 ? (
          <p>La tienda aún no tiene métodos de envío configurados.</p>
        ) : (
          <>
            <ul className="list-inside list-disc space-y-1">
              {data.methods.map((method) => {
                const time = deliveryTime(method)
                return (
                  <li key={method.name}>
                    <strong>{method.name}</strong>
                    {time && ` — ${time}`}
                    {method.description && `. ${method.description}`}
                  </li>
                )
              })}
            </ul>
            <p>Los métodos disponibles dependen de la dirección de despacho.</p>
          </>
        ),
    },
    {
      title: '3. Costo de envío',
      body: (
        <>
          <p>El costo depende de la dirección y del pedido, y se muestra en el checkout antes de pagar.</p>
          {freeShipping != null && (
            <p>
              <strong>Envío gratis</strong> en compras desde {formatCurrency(freeShipping)}, dentro de la cobertura de la tienda.
            </p>
          )}
        </>
      ),
    },
    trackingSection('4'),
    {
      title: '5. Problemas con el envío',
      body: <p>Si su pedido no llega, llega dañado o hay un error en la dirección, contáctenos por los datos de abajo con su número de pedido.</p>,
    },
  ]
}

function trackingSection(number: string): LegalSection {
  return {
    title: `${number}. Seguimiento`,
    body: (
      <p>
        Puede consultar el estado de su pedido con su número de pedido en{' '}
        <Link to="/track-order" className="text-primary underline">Rastrear Pedido</Link>.
      </p>
    ),
  }
}
