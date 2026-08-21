import { useEffect, useState } from 'react'
import api from '../../services/api'
import ProductCard from '../ProductCard'
import Loading from '../Loading'
import type { Product, ProductSearchParams } from '../../types/api'

export default function ProductSection({
  title,
  limit,
  filter,
  tone,
}: {
  title: string
  limit: number
  filter: Pick<ProductSearchParams, 'featured' | 'is_new' | 'on_sale'>
  tone?: 'deals'
}) {
  const [products, setProducts] = useState<Product[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    setLoading(true)
    api.products
      .getAll({ ...filter, per_page: limit, sort_by: 'createdAt', sort_order: 'desc' })
      .then((page) => setProducts(page.content))
      .catch(() => setProducts([]))
      .finally(() => setLoading(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [limit, JSON.stringify(filter)])

  if (!loading && products.length === 0) return null

  return (
    <section className={`mx-auto max-w-6xl px-4 py-10 ${tone === 'deals' ? 'rounded-2xl bg-gradient-to-r from-accent/10 to-primary/10' : ''}`}>
      <h2 className="mb-5 text-xl font-semibold text-dark">{title}</h2>
      {loading ? (
        <Loading />
      ) : (
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4">
          {products.map((product) => (
            <ProductCard key={product.id} product={product} />
          ))}
        </div>
      )}
    </section>
  )
}
