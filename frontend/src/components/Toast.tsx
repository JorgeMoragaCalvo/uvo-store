import { useNotificationStore } from '../stores/useNotificationStore'
import type { NotificationType } from '../stores/useNotificationStore'

const STYLES: Record<NotificationType, string> = {
  success: 'bg-green-600',
  error: 'bg-red-600',
  warning: 'bg-yellow-500',
  info: 'bg-gray-800',
}

export default function Toast() {
  const notifications = useNotificationStore((state) => state.notifications)
  const dismiss = useNotificationStore((state) => state.dismiss)

  if (notifications.length === 0) return null

  return (
    <div className="fixed bottom-4 right-4 z-[100] flex flex-col gap-2">
      {notifications.map((notification) => (
        <div
          key={notification.id}
          onClick={() => dismiss(notification.id)}
          className={`cursor-pointer rounded px-4 py-2.5 text-sm text-white shadow-lg ${STYLES[notification.type]}`}
        >
          {notification.message}
        </div>
      ))}
    </div>
  )
}
