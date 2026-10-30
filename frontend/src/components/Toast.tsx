import { useNotificationStore } from '../stores/useNotificationStore'
import type { NotificationType } from '../stores/useNotificationStore'

// PROD-24b. Contraste de al menos 4,5:1 con el texto blanco: el amarillo (yellow-500) quedaba en ~2:1 y
// el verde (green-600) en ~3,2:1.
const STYLES: Record<NotificationType, string> = {
  success: 'bg-green-700',
  error: 'bg-red-700',
  warning: 'bg-amber-700',
  info: 'bg-gray-800',
}

export default function Toast() {
  const notifications = useNotificationStore((state) => state.notifications)
  const dismiss = useNotificationStore((state) => state.dismiss)

  return (
    // left-4 en el celular: sin él, un mensaje largo llegaba hasta el borde izquierdo de la pantalla.
    //
    // PROD-24b. La región viva queda siempre montada, aunque esté vacía: un lector de pantalla solo anuncia
    // lo que cambia dentro de una región que ya existía. Antes el contenedor nacía con el primer aviso y
    // ninguno se anunciaba. Los errores llevan role="alert", que interrumpe en vez de esperar.
    <div
      aria-live="polite"
      className="pointer-events-none fixed bottom-4 left-4 right-4 z-[100] flex flex-col items-end gap-2 sm:left-auto sm:max-w-sm"
    >
      {notifications.map((notification) => (
        <div
          key={notification.id}
          role={notification.type === 'error' ? 'alert' : undefined}
          className={`pointer-events-auto flex items-start gap-3 rounded px-4 py-2.5 text-sm text-white shadow-lg ${STYLES[notification.type]}`}
        >
          <span className="flex-1">{notification.message}</span>
          {/* Un botón de verdad: antes se cerraba haciendo clic en el aviso entero, que era un <div>. */}
          <button
            type="button"
            onClick={() => dismiss(notification.id)}
            aria-label="Cerrar aviso"
            className="-my-1 -mr-2 flex size-7 shrink-0 items-center justify-center rounded text-white/90 hover:text-white focus-visible:outline-2 focus-visible:outline-white"
          >
            <span aria-hidden="true">✕</span>
          </button>
        </div>
      ))}
    </div>
  )
}
