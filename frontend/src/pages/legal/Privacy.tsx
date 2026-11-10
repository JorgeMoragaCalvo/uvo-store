import { useEffect, useState } from 'react'
import LegalLayout from '../../components/legal/LegalLayout'
import api from '../../services/api'
import type { CheckoutConfig } from '../../types/api'

// GAP-18. Esta página declaraba datos y usos que el sitio no tiene: recolección del RUT (el checkout no lo pide),
// cookies analíticas y de marketing (el storefront no usa cookies), envío de promociones (no hay suscripción) y
// portabilidad (no es un derecho de la Ley N° 19.628). Ahora describe lo que hace la plataforma, y los terceros
// salen de lo que la tienda tiene activo: sus pasarelas de pago. Los textos fijos están listados en
// docs/revision-legal-paginas.md para la revisión legal.

// Sentry se activa en el build (lib/sentry.ts). Si no hay DSN, no se manda nada y no hay que declararlo.
const errorReportingEnabled = Boolean(import.meta.env.VITE_SENTRY_DSN?.trim())

function paymentProviders(config: CheckoutConfig): string[] {
  return [
    config.webpayEnabled && 'Webpay (Transbank)',
    config.mercadopagoEnabled && 'MercadoPago',
    config.stripeEnabled && 'Stripe',
  ].filter((name): name is string => Boolean(name))
}

export default function Privacy() {
  const [providers, setProviders] = useState<string[] | null>(null)

  useEffect(() => {
    let cancelled = false
    api.checkout
      .getConfig()
      .then((config) => {
        if (!cancelled) setProviders(paymentProviders(config))
      })
      .catch(() => {
        if (!cancelled) setProviders(null)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return (
    <LegalLayout
      title="Política de Privacidad"
      intro="Qué datos personales recopilamos al comprar en esta tienda, para qué los usamos y con quién los compartimos, conforme a la Ley N° 19.628 sobre Protección de la Vida Privada."
      sections={[
        {
          title: '1. Información que recopilamos',
          body: (
            <>
              <p><strong>Datos de contacto y despacho:</strong> nombre, apellido, email, teléfono y dirección de despacho (dirección, ciudad, código postal, región y comuna), que usted ingresa al comprar.</p>
              <p><strong>Datos del pedido:</strong> productos, montos, método de envío y estado del pago.</p>
              <p><strong>Datos de pago:</strong> los datos de su tarjeta o cuenta los ingresa directamente en la pasarela de pago. Esta tienda no los recibe ni los almacena.</p>
            </>
          ),
        },
        {
          title: '2. Cómo utilizamos su información',
          body: (
            <ul className="list-inside list-disc space-y-1">
              <li>Procesar su pedido, su pago y el despacho</li>
              <li>Enviarle los correos de su pedido</li>
              <li>Responder sus consultas y solicitudes</li>
              <li>Prevenir fraudes y cumplir obligaciones legales, tributarias y contables</li>
            </ul>
          ),
        },
        {
          title: '3. Con quién compartimos su información',
          body: (
            <>
              <p>No vendemos ni arrendamos su información personal. Solo la compartimos con quienes nos ayudan a completar su compra, y cuando la ley lo exige:</p>
              <ul className="list-inside list-disc space-y-1">
                {providers && providers.length > 0 && <li>Pasarelas de pago: {providers.join(', ')}</li>}
                <li>La empresa de transporte que despacha su pedido</li>
                <li>Los proveedores que alojan el sitio y envían sus correos</li>
                {errorReportingEnabled && <li>Un servicio de reportes de errores técnicos del sitio, que recibe datos de navegación cuando ocurre una falla</li>}
              </ul>
            </>
          ),
        },
        {
          title: '4. Cookies y almacenamiento en su navegador',
          body: (
            <>
              <p>Este sitio no usa cookies de analítica ni de publicidad.</p>
              <p>Guarda su carrito en el almacenamiento local de su navegador para que no lo pierda al volver, y mientras paga guarda el estado de ese pago en el almacenamiento de la sesión. Puede borrarlos desde la configuración de su navegador. Las pasarelas de pago pueden usar sus propias cookies en sus sitios.</p>
            </>
          ),
        },
        {
          title: '5. Seguridad de sus datos',
          body: <p>El sitio se comunica de forma cifrada y el acceso a los datos está restringido. Ningún método de transmisión o almacenamiento electrónico es 100% seguro, por lo que no podemos garantizar una seguridad absoluta.</p>,
        },
        {
          title: '6. Sus derechos',
          body: (
            <>
              <p>Usted puede solicitar:</p>
              <ul className="list-inside list-disc space-y-1">
                <li>Acceso: saber qué datos suyos tenemos</li>
                <li>Rectificación: corregir datos inexactos</li>
                <li>Cancelación: que eliminemos sus datos, salvo los que la ley nos obliga a conservar</li>
                <li>Oposición: que no usemos sus datos para un fin determinado</li>
              </ul>
              <p>Para ejercerlos, escríbanos a los datos de contacto de abajo.</p>
            </>
          ),
        },
        {
          title: '7. Retención de datos',
          body: <p>Conservamos su información el tiempo necesario para los fines descritos y el que exijan nuestras obligaciones legales, tributarias y contables.</p>,
        },
        {
          title: '8. Privacidad de menores',
          body: <p>El sitio no está dirigido a menores de 18 años. Si detectamos que recopilamos datos de un menor sin consentimiento de sus padres, los eliminaremos.</p>,
        },
        {
          title: '9. Cambios a esta política',
          body: <p>Podemos actualizar esta política. Le recomendamos revisarla para estar informado de cómo protegemos su información.</p>,
        },
      ]}
    />
  )
}
