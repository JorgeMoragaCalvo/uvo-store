import LegalLayout from '../../components/legal/LegalLayout'

export default function Terms() {
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
              <li>Mantener la confidencialidad de su cuenta y contraseña</li>
              <li>No utilizar el sitio para actividades ilegales o no autorizadas</li>
              <li>No intentar acceder a áreas restringidas del sitio</li>
              <li>No transmitir virus o código malicioso</li>
            </ul>
          ),
        },
        {
          title: '3. Registro de Cuenta',
          body: <p>Para realizar compras puede ser necesario crear una cuenta. Usted es responsable de mantener la confidencialidad de su información de cuenta y de toda actividad que ocurra bajo ella.</p>,
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
              <p>Todos los precios están expresados en pesos chilenos (CLP) e incluyen IVA cuando corresponda, y están sujetos a cambio sin previo aviso.</p>
              <p>Utilizamos tecnología de encriptación SSL para proteger su información de pago; no almacenamos datos completos de tarjetas en nuestros servidores.</p>
            </>
          ),
        },
        {
          title: '6. Envíos y Entregas',
          body: <p>Los tiempos de entrega pueden variar según la ubicación. El costo exacto de envío se muestra antes de finalizar la compra. No nos responsabilizamos por retrasos causados por el servicio de mensajería.</p>,
        },
        {
          title: '7. Devoluciones y Reembolsos',
          body: (
            <p>
              De acuerdo con la Ley del Consumidor chilena (Ley 19.496), usted tiene derecho a retractarse de su compra dentro de 10 días corridos desde la recepción del producto, siempre que no haya sido usado y conserve su embalaje original. Ver la{' '}
              <a href="/politica-de-devoluciones" className="text-primary underline">Política de Devoluciones</a> para más detalle.
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
              <a href="/politica-de-privacidad" className="text-primary underline">Política de Privacidad</a>.
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
