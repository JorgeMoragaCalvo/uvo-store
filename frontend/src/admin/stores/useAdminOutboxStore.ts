import { create } from 'zustand'
import type { Page } from '@/types/api'
import adminApi from '@/admin/services/adminApi'
import type { OutboxFailure } from '@/admin/types/admin'

// GAP-04. La página «Envíos fallidos»: las entregas del outbox que esperan a una persona.
interface AdminOutboxState {
  data: Page<OutboxFailure> | null
  loading: boolean
  fetchFailed: (page: number) => Promise<void>
  retry: (id: number, note: string, posChecked: boolean) => Promise<void>
  resolve: (id: number, note: string) => Promise<void>
}

export const useAdminOutboxStore = create<AdminOutboxState>((set, get) => {
  // Ya no está fallida: sale de la lista sin volver a pedirla.
  function drop(id: number) {
    const data = get().data
    if (data) {
      set({
        data: {
          ...data,
          content: data.content.filter((failure) => failure.id !== id),
          totalElements: Math.max(data.totalElements - 1, 0),
        },
      })
    }
  }

  return {
    data: null,
    loading: false,
    fetchFailed: async (page) => {
      set({ loading: true })
      try {
        const data = await adminApi.outbox.failed(page)
        set({ data, loading: false })
      } catch (err) {
        set({ loading: false })
        throw err
      }
    },
    retry: async (id, note, posChecked) => {
      await adminApi.outbox.retry(id, note, posChecked)
      drop(id)
    },
    resolve: async (id, note) => {
      await adminApi.outbox.resolve(id, note)
      drop(id)
    },
  }
})
