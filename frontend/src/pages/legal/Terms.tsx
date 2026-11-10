import { Link } from 'react-router-dom'
import LegalLayout from '../../components/legal/LegalLayout'
import { useStoreSettingsStore } from '../../stores/useStoreSettingsStore'

// GAP-18. El IVA y el retracto salen de la configuración de la tienda: el texto fijo decía «incluyen IVA cuando
// corresponda» y daba el retracto por hecho, aunque cada tienda decide ambas cosas. Los textos fijos están
// listados en docs/revision-legal-paginas.md para la revisión legal.
export default function Terms() {
  const settings = useStoreSettingsStore((state) => state.settings)
  const withdrawalExcluded = settings?.withdrawalRightExcluded ?? false
  // Sin la configuración cargada todavía no se afirma ninguna de las dos formas.
  const taxSentence =
    settings == null
      ? 'El checkout muestra el IVA antes de finalizar la compra.'
      : settings.pricesIncludeTax
        ? 'Los precios publicados incluyen IVA.'
        : 'Los precios publicados no incluyen IVA: el IVA se suma en el checkout, antes de finalizar la compra.'

  return (
    <LegalLayout
      title="Términos y Condiciones"
      intro="Por favor, lea estos términos cuidadosamente antes de utilizar nuestros servicios."
      sections={[
        {
          title: '1. Aceptación de los Términos',
          body: (
            <>
              <p>Al acceder y utilizar este sitio web usted acepta cumplir con estos Términos y Condiciones de Uso. Si no está de acuerdo con alguna parte de estos términos, no debe utilizar el sitio.</p>
              <p>Nos reservamos el derecho de modificar estos términos en cualquier momento. Es su responsabilidad revisarlos periódicamente.</p>
            </>
          ),
        },
        {
          title: '2. Uso del Sitio Web',
          body: (
            <ul className="list-inside list-disc space-y-1">
              <li>Proporcionar información veraz, precisa y completa al realizar un pedido</li>
              <li>No utilizar el sitio para actividades ilegales o no autorizadas</li>
              <li>No intentar acceder a áreas restringidas del sitio</li>
              <li>No transmitir virus o código malicioso</li>
            </ul>
          ),
        },
        {
          // PROD-07: esta sección decía que «puede ser necesario crear una cuenta» y pedía al comprador
          // cuidar una contraseña que nunca se le da. Las cuentas de comprador están diferidas: no hay
          // registro, no hay ingreso y no se envía ninguna invitación. Decir lo contrario en los
          // términos es prometer algo que el sitio no hace.
          title: '3. Compras sin cuenta',
          body: <p>No es necesario crear una cuenta para comprar: el pedido se realiza como invitado y su estado se consulta con el número de pedido. Los datos que usted entregue en el checkout se utilizan para procesar y despachar esa compra.</p>,
        },
        {
          title: '4. Productos y Servicios',
          body: (
            <>
              <p><strong>Descripción:</strong> hacemos lo posible por mostrar con precisión colores e imágenes, pero no garantizamos que la visualización en su monitor sea exacta.</p>
              <p><strong>Disponibilidad:</strong> todos los productos están sujetos a disponibilidad; podemos discontinuar un producto en cualquier momento.</p>
              <p><strong>Errores:</strong> si un producto se lista a un precio incorrecto por error, nos reservamos el derecho de cancelar el pedido correspondiente.</p>
            </>
          ),
        },
        {
          title: '5. Precios y Pagos',
          body: (
            <>
              <p>Todos los precios están expresados en pesos chilenos (CLP) y están sujetos a cambio sin previo aviso. {taxSentence}</p>
              <p>Utilizamos tecnología de encriptación SSL para proteger su información de pago; no almacenamos datos completos de tarjetas en nuestros servidores.</p>
            </>
          ),
        },
        {
          title: '6. Envíos y Entregas',
          body: (
            <p>
              Los plazos de entrega dependen del método de envío y de la dirección. El costo exacto de envío se muestra antes de finalizar la compra. Ver la{' '}
              <Link to="/politica-de-envios" className="text-primary underline">Política de Envíos</Link>.
            </p>
          ),
        },
        {
          title: '7. Devoluciones y Reembolsos',
          body: (
            <p>
              {withdrawalExcluded
                ? 'Esta tienda no ofrece derecho a retracto en las compras realizadas en este sitio, conforme al artículo 3 bis, letra b), de la Ley N° 19.496. Sus productos mantienen la garantía legal.'
                : 'De acuerdo con la Ley N° 19.496, usted tiene derecho a retractarse de su compra dentro de 10 días corridos desde la recepción del producto, siempre que no haya sido usado y conserve sus elementos originales.'}{' '}
              Ver la <Link to="/politica-de-devoluciones" className="text-primary underline">Política de Devoluciones</Link> para más detalle.
            </p>
          ),
        },
        {
          title: '8. Propiedad Intelectual',
          body: <p>Todo el contenido de este sitio (textos, gráficos, logos, imágenes) está protegido por las leyes de propiedad intelectual de Chile e internacionales. No está permitido reproducirlo o distribuirlo sin consentimiento expreso.</p>,
        },
        {
          title: '9. Limitación de Responsabilidad',
          body: <p>En la máxima medida permitida por la ley, no seremos responsables por daños indirectos, incidentales o consecuentes. Nuestra responsabilidad total no excederá el monto pagado por el producto o servicio en cuestión.</p>,
        },
        {
          title: '10. Protección de Datos Personales',
          body: (
            <p>
              El tratamiento de sus datos se realiza conforme a la Ley N° 19.628 sobre Protección de la Vida Privada. Ver la{' '}
              <Link to="/politica-de-privacidad" className="text-primary underline">Política de Privacidad</Link>.
            </p>
          ),
        },
        {
          title: '11. Ley Aplicable y Jurisdicción',
          body: <p>Estos términos se rigen por las leyes de Chile. Cualquier disputa estará sujeta a la jurisdicción de los tribunales competentes de Chile.</p>,
        },
      ]}
    />
  )
}
