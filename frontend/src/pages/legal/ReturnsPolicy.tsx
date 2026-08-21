import LegalLayout from '../../components/legal/LegalLayout'

export default function ReturnsPolicy() {
  return (
    <LegalLayout
      title="Política de Devoluciones"
      intro="Información sobre nuestras garantías, devoluciones y cambios de productos."
      sections={[
        {
          title: '1. Derecho de Retracto (Ley del Consumidor)',
          body: (
            <>
              <p>De acuerdo con la Ley N° 19.496 de Protección de los Derechos del Consumidor de Chile, usted tiene derecho a retractarse de su compra dentro de 10 días corridos desde la recepción del producto, sin necesidad de justificar su decisión.</p>
              <p>Condiciones: el producto no debe haber sido usado, debe conservar su embalaje original, incluir etiquetas y accesorios, y notificarnos dentro del plazo.</p>
            </>
          ),
        },
        {
          title: '2. Cómo Solicitar una Devolución',
          body: (
            <ol className="list-inside list-decimal space-y-1">
              <li>Contáctenos indicando número de orden, producto(s), motivo y fotos (opcional)</li>
              <li>Recibirá un número RMA e instrucciones de envío dentro de 24-48 horas</li>
              <li>Empaque el producto de forma segura, con el RMA visible</li>
              <li>Envíe el producto (el costo de envío de devolución corre por cuenta del cliente, salvo productos defectuosos)</li>
              <li>Una vez verificado, procesamos el reembolso en 10 días hábiles</li>
            </ol>
          ),
        },
        {
          title: '3. Productos Defectuosos o Dañados',
          body: <p>Si recibe un producto defectuoso, dañado o incorrecto, ofrecemos reemplazo inmediato sin costo o reembolso completo, cubriendo nosotros el envío de devolución. Debe notificarnos dentro de las 48 horas siguientes a la recepción, incluyendo fotos del defecto o daño.</p>,
        },
        {
          title: '4. Cambios de Producto',
          body: (
            <>
              <p><strong>Cambio por talla:</strong> sin costo (sujeto a stock), producto sin uso y con etiquetas, plazo de 30 días.</p>
              <p><strong>Cambio por color/modelo:</strong> disponible dentro de los 10 días corridos, sujeto a disponibilidad.</p>
            </>
          ),
        },
        {
          title: '5. Proceso de Reembolso',
          body: <p>El reembolso se realiza al mismo método de pago original, dentro de 10 días hábiles desde que recibimos y verificamos el producto. Se requiere boleta o factura, el producto en su embalaje original y el número RMA.</p>,
        },
        {
          title: '6. Productos No Retornables',
          body: (
            <p>
              Por higiene y seguridad, no son retornables: ropa interior, trajes de baño, productos de belleza abiertos, cosméticos usados, y productos personalizados o hechos a medida — salvo que lleguen defectuosos o dañados, caso en el que aplican las mismas políticas de garantía.
            </p>
          ),
        },
        {
          title: '7. Garantía de Productos',
          body: (
            <>
              <p><strong>Garantía legal (3 meses):</strong> cubre defectos de fabricación y fallas no atribuibles al uso normal — incluye reparación, cambio o devolución del dinero.</p>
              <p><strong>Garantía extendida (opcional):</strong> algunos productos ofrecen garantía adicional; consulte la descripción del producto.</p>
            </>
          ),
        },
      ]}
    />
  )
}
