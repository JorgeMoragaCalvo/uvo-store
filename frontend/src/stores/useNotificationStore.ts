import { create } from 'zustand'

export type NotificationType = 'success' | 'error' | 'warning' | 'info'

export interface Notification {
  id: number
  message: string
  type: NotificationType
}

interface NotificationState {
  notifications: Notification[]
  notify: (message: string, type?: NotificationType) => void
  dismiss: (id: number) => void
}

let nextId = 1

export const useNotificationStore = create<NotificationState>((set, get) => ({
  notifications: [],

  notify(message, type = 'info') {
    const id = nextId++
    set((state) => ({ notifications: [...state.notifications, { id, message, type }] }))
    // PROD-24b. 3,3 s no alcanzaba para leer un aviso largo, ni para llegar a él con un lector de pantalla.
    // Los errores duran más: suelen pedir que el comprador haga algo.
    setTimeout(() => get().dismiss(id), type === 'error' ? 8000 : 5000)
  },

  dismiss(id) {
    set((state) => ({ notifications: state.notifications.filter((n) => n.id !== id) }))
  },
}))
