import LegalLayout from '../../components/legal/LegalLayout'

export default function ShippingPolicy() {
  return (
    <LegalLayout
      title="Política de Envíos"
      intro="Información sobre nuestras opciones de envío, tiempos de entrega y costos."
      sections={[
        {
          title: '1. Zonas de Envío',
          body: (
            <ul className="list-inside list-disc space-y-1">
              <li><strong>Región Metropolitana</strong> — Santiago y alrededores: 24-48 horas hábiles</li>
              <li><strong>Zona Centro</strong> — V, VI, VII, VIII regiones: 2-4 días hábiles</li>
              <li><strong>Zona Extrema</strong> — I, II, XI, XII, XV regiones: 5-7 días hábiles</li>
            </ul>
          ),
        },
        {
          title: '2. Métodos de Envío',
          body: (
            <ul className="list-inside list-disc space-y-1">
              <li><strong>Envío Express</strong> — 24-48 horas en RM, desde $5.990, con tracking en tiempo real</li>
              <li><strong>Envío Estándar</strong> — 3-5 días hábiles a todo Chile, desde $3.990</li>
              <li><strong>Envío Gratis</strong> — en compras sobre $50.000 en Región Metropolitana</li>
            </ul>
          ),
        },
        {
          title: '3. Costos de Envío',
          body: <p>El costo se calcula automáticamente según destino, peso/volumen del pedido, valor de compra y velocidad elegida. El costo exacto se muestra en el checkout antes de finalizar la compra.</p>,
        },
        {
          title: '4. Tiempos de Procesamiento',
          body: (
            <ol className="list-inside list-decimal space-y-1">
              <li>Confirmación del pago (5-30 minutos)</li>
              <li>Preparación del pedido (1-2 días hábiles)</li>
              <li>Entrega al courier (siguiente día hábil)</li>
              <li>Entrega según zona de destino</li>
            </ol>
          ),
        },
        {
          title: '5. Seguimiento de Envío',
          body: (
            <p>
              Recibirá un email con el número de tracking, o puede ingresar su número de pedido en{' '}
              <a href="/track-order" className="text-primary underline">Rastrear Pedido</a>.
            </p>
          ),
        },
        {
          title: '6. Restricciones y Excepciones',
          body: (
            <ul className="list-inside list-disc space-y-1">
              <li>No realizamos entregas en Isla de Pascua ni Territorio Antártico</li>
              <li>Zonas remotas pueden tener costos adicionales</li>
              <li>Entregas solo en días hábiles (lunes a viernes, 9:00-18:00)</li>
              <li>No hay entregas en feriados</li>
            </ul>
          ),
        },
        {
          title: '7. Problemas con el Envío',
          body: (
            <ul className="list-inside list-disc space-y-1">
              <li><strong>Paquete perdido:</strong> si pasaron más de 10 días desde el despacho, contáctenos para investigar con el courier</li>
              <li><strong>Paquete dañado:</strong> tome fotos y contáctenos dentro de 48 horas</li>
              <li><strong>Dirección incorrecta:</strong> contáctenos de inmediato; pueden aplicar costos adicionales</li>
              <li><strong>Retraso en entrega:</strong> le informaremos proactivamente y buscaremos una solución</li>
            </ul>
          ),
        },
      ]}
    />
  )
}
