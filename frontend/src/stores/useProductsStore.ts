import { create } from 'zustand'
import api from '../services/api'
import { friendlyError } from '../services/errors'
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
      // PROD-24b. Un texto para el comprador, no el de axios.
      set({ loading: false, error: friendlyError(error, 'No pudimos cargar los productos. Inténtalo de nuevo en unos minutos.') })
    }
  },
}))
