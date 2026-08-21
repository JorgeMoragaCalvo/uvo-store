import LegalLayout from '../../components/legal/LegalLayout'

export default function Privacy() {
  return (
    <LegalLayout
      title="Política de Privacidad"
      intro="Su privacidad es importante para nosotros. Esta política describe cómo recopilamos y utilizamos su información, conforme a la Ley N° 19.628 sobre Protección de la Vida Privada de Chile."
      sections={[
        {
          title: '1. Información que Recopilamos',
          body: (
            <>
              <p><strong>Información personal:</strong> nombre, email, teléfono, dirección de envío/facturación, RUT, información de pago (procesada de forma segura por terceros).</p>
              <p><strong>Información de navegación:</strong> IP, tipo de navegador, sistema operativo, páginas visitadas, cookies.</p>
              <p><strong>Información de transacciones:</strong> historial de pedidos, productos adquiridos, montos y preferencias.</p>
            </>
          ),
        },
        {
          title: '2. Cómo Utilizamos su Información',
          body: (
            <ul className="list-inside list-disc space-y-1">
              <li>Procesar pedidos, pagos y envíos</li>
              <li>Enviar confirmaciones y responder consultas</li>
              <li>Mejorar la experiencia del sitio</li>
              <li>Enviar promociones (solo si usted lo autoriza)</li>
              <li>Prevenir fraude y cumplir obligaciones legales</li>
            </ul>
          ),
        },
        {
          title: '3. Compartir su Información',
          body: <p>No vendemos ni alquilamos su información personal. Solo la compartimos con proveedores de servicios (envío, pagos, hosting), cuando lo exige la ley, o en caso de fusión/adquisición de la empresa.</p>,
        },
        {
          title: '4. Uso de Cookies',
          body: <p>Usamos cookies esenciales (carrito, sesión), analíticas, de marketing y de preferencias. Puede configurar su navegador para rechazarlas, aunque esto puede afectar la funcionalidad del sitio.</p>,
        },
        {
          title: '5. Seguridad de sus Datos',
          body: <p>Implementamos encriptación SSL, servidores seguros y acceso restringido a los datos. Ningún método de transmisión o almacenamiento electrónico es 100% seguro, por lo que no podemos garantizar seguridad absoluta.</p>,
        },
        {
          title: '6. Sus Derechos',
          body: (
            <ul className="list-inside list-disc space-y-1">
              <li>Acceso: solicitar qué datos tenemos sobre usted</li>
              <li>Rectificación: corregir datos inexactos</li>
              <li>Cancelación: solicitar la eliminación de sus datos</li>
              <li>Oposición: oponerse al uso de sus datos para marketing</li>
              <li>Portabilidad: recibir sus datos en formato estructurado</li>
            </ul>
          ),
        },
        {
          title: '7. Retención de Datos',
          body: <p>Conservamos su información el tiempo necesario para los fines descritos. Los datos de transacciones se conservan 7 años por obligaciones tributarias y contables.</p>,
        },
        {
          title: '8. Privacidad de Menores',
          body: <p>El sitio no está dirigido a menores de 18 años. Si detectamos que recopilamos datos de un menor sin consentimiento parental, los eliminaremos de inmediato.</p>,
        },
        {
          title: '9. Cambios a esta Política',
          body: <p>Podemos actualizar esta política periódicamente. Le recomendamos revisarla para estar informado de cómo protegemos su información.</p>,
        },
      ]}
    />
  )
}
