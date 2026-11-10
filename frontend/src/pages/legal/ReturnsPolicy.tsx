import LegalLayout from '../../components/legal/LegalLayout'
import type { LegalSection } from '../../components/legal/LegalLayout'
import { useStoreSettingsStore } from '../../stores/useStoreSettingsStore'

// GAP-18. Esta página fijaba la garantía legal en 3 meses (la Ley N° 21.398 la llevó a 6), exigía avisar los
// defectos en 48 horas (lo que recorta esa garantía) y prometía números RMA, cambios por talla a 30 días y una
// lista de productos no retornables que ninguna tienda configuró. Queda lo que fija la ley y lo que la tienda
// decide en su configuración: si excluye el derecho a retracto. Los textos fijos están listados en
// docs/revision-legal-paginas.md para la revisión legal.
export default function ReturnsPolicy() {
  const settings = useStoreSettingsStore((state) => state.settings)
  const withdrawalExcluded = settings?.withdrawalRightExcluded ?? false

  const sections: LegalSection[] = [
    {
      title: '1. Derecho a retracto',
      body: withdrawalExcluded ? (
        <p>
          Esta tienda <strong>no ofrece derecho a retracto</strong> en las compras realizadas en este sitio, conforme al artículo 3 bis, letra b), de la Ley N° 19.496 sobre Protección de los Derechos de los Consumidores. Esto no afecta la garantía legal descrita más abajo.
        </p>
      ) : (
        <>
          <p>De acuerdo con el artículo 3 bis, letra b), de la Ley N° 19.496 sobre Protección de los Derechos de los Consumidores, usted puede retractarse de su compra dentro de 10 días corridos desde que recibe el producto, sin necesidad de justificar su decisión.</p>
          <p>Para ejercerlo, el producto debe devolverse sin uso y con sus elementos originales: embalaje, etiquetas, accesorios y manuales.</p>
        </>
      ),
    },
    {
      title: '2. Garantía legal',
      body: (
        <>
          <p>Si el producto presenta fallas o defectos de fabricación, o no corresponde a lo comprado, usted tiene una <strong>garantía legal de 6 meses</strong> desde que lo recibe.</p>
          <p>Dentro de ese plazo puede elegir entre la reparación gratuita, el cambio del producto o la devolución del dinero pagado.</p>
        </>
      ),
    },
    {
      title: '3. Cómo solicitar una devolución o un cambio',
      body: <p>Contáctenos por los datos de abajo indicando su número de pedido, el producto y el motivo. Le responderemos con las instrucciones para devolverlo.</p>,
    },
    {
      title: '4. Reembolsos',
      body: <p>Cuando corresponde devolver el dinero, el reembolso se hace al mismo medio de pago con el que se compró.</p>,
    },
  ]

  return (
    <LegalLayout
      title="Política de Devoluciones"
      intro="Sus derechos de retracto y de garantía, y cómo solicitar una devolución o un cambio."
      sections={sections}
    />
  )
}
