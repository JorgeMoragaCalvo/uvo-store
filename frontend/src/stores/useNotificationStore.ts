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
    setTimeout(() => get().dismiss(id), 3300)
  },

  dismiss(id) {
    set((state) => ({ notifications: state.notifications.filter((n) => n.id !== id) }))
  },
}))
