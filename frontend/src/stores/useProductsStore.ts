import { create } from 'zustand'
import api from '../services/api'
import type { Product, ProductSearchParams } from '../types/api'

interface ProductsState {
  products: Product[]
  totalElements: number
  totalPages: number
  page: number
  loading: boolean
  error: string | null
  search: (params: ProductSearchParams) => Promise<void>
}

export const useProductsStore = create<ProductsState>((set) => ({
  products: [],
  totalElements: 0,
  totalPages: 0,
  page: 1,
  loading: false,
  error: null,

  async search(params) {
    set({ loading: true, error: null })
    try {
      const result = await api.products.getAll(params)
      set({
        products: result.content,
        totalElements: result.totalElements,
        totalPages: result.totalPages,
        page: result.number + 1,
        loading: false,
      })
    } catch (error) {
      set({ loading: false, error: (error as { message?: string }).message ?? 'Error loading products' })
    }
  },
}))
