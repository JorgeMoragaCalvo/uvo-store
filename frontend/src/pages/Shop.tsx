import { useEffect } from 'react'
import { useParams, useSearchParams } from 'react-router-dom'
import { useProductsStore } from '../stores/useProductsStore'
import { useStoreSettingsStore } from '../stores/useStoreSettingsStore'
import ProductCard from '../components/ProductCard'
import Loading from '../components/Loading'
import type { ProductSearchParams } from '../types/api'

const PER_PAGE = 12

const SORT_OPTIONS: { label: string; sortBy: ProductSearchParams['sort_by']; sortOrder: ProductSearchParams['sort_order'] }[] = [
  { label: 'Más recientes', sortBy: 'createdAt', sortOrder: 'desc' },
  { label: 'Precio: menor a mayor', sortBy: 'price', sortOrder: 'asc' },
  { label: 'Precio: mayor a menor', sortBy: 'price', sortOrder: 'desc' },
  { label: 'Nombre A-Z', sortBy: 'name', sortOrder: 'asc' },
]

// Also mounted at /category/:slug — the route param wins over ?category= when both are present.
export default function Shop() {
  const { slug } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()
  const { products, totalPages, page, loading } = useProductsStore()
  const search = useProductsStore((state) => state.search)
  const categories = useStoreSettingsStore((state) => state.categories)

  const category = slug ?? searchParams.get('category') ?? ''
  const searchTerm = searchParams.get('search') ?? ''
  const minPrice = searchParams.get('min_price') ?? ''
  const maxPrice = searchParams.get('max_price') ?? ''
  const type = (searchParams.get('type') as ProductSearchParams['type']) ?? ''
  const inStock = searchParams.get('in_stock') === 'true'
  const onSale = searchParams.get('on_sale') === 'true'
  const sortBy = (searchParams.get('sort_by') as ProductSearchParams['sort_by']) ?? 'createdAt'
  const sortOrder = (searchParams.get('sort_order') as ProductSearchParams['sort_order']) ?? 'desc'
  const currentPage = Number(searchParams.get('page') ?? '1')

  useEffect(() => {
    search({
      search: searchTerm || undefined,
      category: category || undefined,
      type: type || undefined,
      in_stock: inStock || undefined,
      on_sale: onSale || undefined,
      min_price: minPrice ? Number(minPrice) : undefined,
      max_price: maxPrice ? Number(maxPrice) : undefined,
      sort_by: sortBy,
      sort_order: sortOrder,
      per_page: PER_PAGE,
      page: currentPage,
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [category, searchTerm, type, inStock, onSale, minPrice, maxPrice, sortBy, sortOrder, currentPage])

  function updateParam(key: string, value: string | null, resetPage = true) {
    const next = new URLSearchParams(searchParams)
    if (value) next.set(key, value)
    else next.delete(key)
    if (resetPage) next.delete('page')
    setSearchParams(next)
  }

  function clearFilters() {
    setSearchParams({})
  }

  const title = slug ? categories.find((c) => c.slug === slug)?.name ?? 'Categoría' : 'Todos los Productos'

  return (
    <div className="mx-auto max-w-6xl p-4 py-8">
      <h1 className="mb-6 text-2xl font-semibold text-dark">{title}</h1>

      <div className="grid gap-6 lg:grid-cols-4">
        <aside className="space-y-6 lg:col-span-1">
          <div>
            <label className="mb-1 block text-sm font-medium text-dark">Buscar</label>
            <input
              type="search"
              defaultValue={searchTerm}
              onBlur={(event) => updateParam('search', event.target.value || null)}
              placeholder="Buscar productos..."
              className="w-full rounded border border-gray-400 px-3 py-1.5 text-sm"
            />
          </div>

          {!slug && (
            <div>
              <h3 className="mb-2 text-sm font-medium text-dark">Categoría</h3>
              <div className="space-y-1 text-sm text-secondary">
                <label className="flex items-center gap-2">
                  <input type="radio" checked={category === ''} onChange={() => updateParam('category', null)} />
                  Todas
                </label>
                {categories.map((c) => (
                  <label key={c.id} className="flex items-center gap-2">
                    <input type="radio" checked={category === c.slug} onChange={() => updateParam('category', c.slug)} />
                    {c.name}
                  </label>
                ))}
              </div>
            </div>
          )}

          <div>
            <h3 className="mb-2 text-sm font-medium text-dark">Precio</h3>
            <div className="flex items-center gap-2">
              <input
                type="number"
                defaultValue={minPrice}
                onBlur={(event) => updateParam('min_price', event.target.value || null)}
                placeholder="Mín"
                className="w-full rounded border border-gray-400 px-2 py-1.5 text-sm"
              />
              <span className="text-secondary">-</span>
              <input
                type="number"
                defaultValue={maxPrice}
                onBlur={(event) => updateParam('max_price', event.target.value || null)}
                placeholder="Máx"
                className="w-full rounded border border-gray-400 px-2 py-1.5 text-sm"
              />
            </div>
          </div>

          <div>
            <h3 className="mb-2 text-sm font-medium text-dark">Tipo</h3>
            <select
              value={type}
              onChange={(event) => updateParam('type', event.target.value || null)}
              className="w-full rounded border border-gray-400 px-2 py-1.5 text-sm"
            >
              <option value="">Todos</option>
              <option value="SIMPLE">Simple</option>
              <option value="VARIABLE">Con variaciones</option>
            </select>
          </div>

          <label className="flex items-center gap-2 text-sm text-secondary">
            <input
              type="checkbox"
              checked={inStock}
              onChange={(event) => updateParam('in_stock', event.target.checked ? 'true' : null)}
            />
            Solo en stock
          </label>

          <label className="flex items-center gap-2 text-sm text-secondary">
            <input
              type="checkbox"
              checked={onSale}
              onChange={(event) => updateParam('on_sale', event.target.checked ? 'true' : null)}
            />
            En oferta
          </label>

          <button type="button" onClick={clearFilters} className="text-sm text-primary underline">
            Limpiar filtros
          </button>
        </aside>

        <div className="lg:col-span-3">
          <div className="mb-4 flex items-center justify-end">
            <select
              value={`${sortBy}:${sortOrder}`}
              onChange={(event) => {
                const [newSortBy, newSortOrder] = event.target.value.split(':')
                const next = new URLSearchParams(searchParams)
                next.set('sort_by', newSortBy)
                next.set('sort_order', newSortOrder)
                next.delete('page')
                setSearchParams(next)
              }}
              className="rounded border border-gray-400 px-2 py-1.5 text-sm"
            >
              {SORT_OPTIONS.map((option) => (
                <option key={option.label} value={`${option.sortBy}:${option.sortOrder}`}>
                  {option.label}
                </option>
              ))}
            </select>
          </div>

          {loading ? (
            <Loading size="lg" />
          ) : products.length === 0 ? (
            <p className="py-12 text-center text-secondary">No se encontraron productos.</p>
          ) : (
            <div className="grid grid-cols-2 gap-4 sm:grid-cols-3">
              {products.map((product) => (
                <ProductCard key={product.id} product={product} />
              ))}
            </div>
          )}

          {totalPages > 1 && (
            <div className="mt-6 flex justify-center gap-2">
              {Array.from({ length: totalPages }, (_, i) => i + 1).map((p) => (
                <button
                  key={p}
                  type="button"
                  onClick={() => {
                    const next = new URLSearchParams(searchParams)
                    next.set('page', String(p))
                    setSearchParams(next)
                  }}
                  className={`h-8 w-8 rounded text-sm ${p === page ? 'bg-primary text-white' : 'border border-gray-400 text-dark'}`}
                >
                  {p}
                </button>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
