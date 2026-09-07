import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import api from '../services/api'
import { useCartStore } from '../stores/useCartStore'
import { useNotificationStore } from '../stores/useNotificationStore'
import ProductCard from '../components/ProductCard'
import Loading from '../components/Loading'
import { formatCurrency } from '../utils/currency'
import type { Product, ProductVariation } from '../types/api'

function buildAttributeGroups(variations: ProductVariation[]): Record<string, string[]> {
  const groups: Record<string, string[]> = {}
  for (const variation of variations) {
    for (const [name, value] of Object.entries(variation.attributes)) {
      if (!groups[name]) groups[name] = []
      if (!groups[name].includes(value)) groups[name].push(value)
    }
  }
  return groups
}

function findMatchingVariation(variations: ProductVariation[], selected: Record<string, string>): ProductVariation | null {
  return (
    variations.find((variation) =>
      Object.entries(variation.attributes).every(([name, value]) => selected[name] === value),
    ) ?? null
  )
}

export default function ProductDetail() {
  const { slug } = useParams()
  const navigate = useNavigate()
  const addItem = useCartStore((state) => state.addItem)
  const ensureItem = useCartStore((state) => state.ensureItem)
  const notify = useNotificationStore((state) => state.notify)

  const [product, setProduct] = useState<Product | null>(null)
  const [related, setRelated] = useState<Product[]>([])
  const [loading, setLoading] = useState(true)
  const [notFound, setNotFound] = useState(false)
  const [activeImage, setActiveImage] = useState<string | null>(null)
  const [selectedAttributes, setSelectedAttributes] = useState<Record<string, string>>({})
  const [quantity, setQuantity] = useState(1)

  useEffect(() => {
    if (!slug) return
    let cancelled = false

    async function loadProduct() {
      setLoading(true)
      setNotFound(false)
      setSelectedAttributes({})
      setQuantity(1)

      try {
        const data = await api.products.getBySlug(slug!)
        if (cancelled) return
        setProduct(data)
        setActiveImage(data.featuredImage ?? data.images[0]?.url ?? null)
      } catch {
        if (!cancelled) setNotFound(true)
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    async function loadRelated() {
      try {
        const related = await api.products.getRelated(slug!)
        if (!cancelled) setRelated(related)
      } catch {
        if (!cancelled) setRelated([])
      }
    }

    loadProduct()
    loadRelated()
    return () => {
      cancelled = true
    }
  }, [slug])

  const attributeGroups = useMemo(() => (product ? buildAttributeGroups(product.variations) : {}), [product])
  const isVariable = product?.productType === 'variable'
  const selectedVariation = useMemo(
    () => (product && isVariable ? findMatchingVariation(product.variations, selectedAttributes) : null),
    [product, isVariable, selectedAttributes],
  )
  const allAttributesSelected = product ? Object.keys(attributeGroups).length === Object.keys(selectedAttributes).length : false

  const displayPrice = selectedVariation?.formattedPrice ?? product?.formattedPrice
  const stock = selectedVariation ? selectedVariation.stock : product?.stock ?? 0
  const canAddToCart = product && (!isVariable || (allAttributesSelected && selectedVariation && selectedVariation.inStock))

  function handleSelectAttribute(name: string, value: string) {
    setSelectedAttributes((prev) => ({ ...prev, [name]: value }))
  }

  function handleAddToCart() {
    if (!product || !canAddToCart) return
    addItem(product, quantity, selectedVariation)
    notify(`${product.name} agregado al carrito`, 'success')
  }

  function handleBuyNow() {
    if (!product || !canAddToCart) return
    // Neither adds nor replaces: if the product is already in the cart it's left alone, and only the
    // navigation happens. Adding here used to leave two units after "Agregar al Carrito"; setting it
    // to the selector's value instead used to REDUCE a cart built by pressing "Agregar" twice, since
    // the selector stays at 1.
    ensureItem(product, quantity, selectedVariation)
    navigate('/checkout')
  }

  if (loading) {
    return <Loading size="lg" />
  }

  if (notFound || !product) {
    return <p className="p-8 text-center text-secondary">Producto no encontrado.</p>
  }

  const gallery = product.images.length > 0 ? product.images.map((image) => image.url) : product.featuredImage ? [product.featuredImage] : []

  return (
    <div className="mx-auto max-w-5xl p-4 py-8">
      <div className="grid gap-8 md:grid-cols-2">
        <div>
          <div className="aspect-square overflow-hidden rounded-lg bg-gray-100">
            {activeImage && <img src={activeImage} alt={product.name} loading="eager" className="h-full w-full object-cover" />}
          </div>
          {gallery.length > 1 && (
            <div className="mt-3 flex gap-2">
              {gallery.map((url) => (
                <button
                  key={url}
                  type="button"
                  onClick={() => setActiveImage(url)}
                  className={`h-16 w-16 overflow-hidden rounded border ${activeImage === url ? 'border-primary' : 'border-gray-400'}`}
                >
                  <img src={url} alt="" loading="lazy" className="h-full w-full object-cover" />
                </button>
              ))}
            </div>
          )}
        </div>

        <div>
          {product.category && <p className="mb-1 text-sm text-secondary">{product.category.name}</p>}
          <h1 className="mb-2 text-2xl font-semibold text-dark">{product.name}</h1>

          <div className="mb-3 flex items-center gap-2">
            <span className="text-xl font-bold text-dark">{displayPrice}</span>
            {selectedVariation?.compareAtPrice && (
              <span className="text-sm text-secondary line-through">{formatCurrency(selectedVariation.compareAtPrice)}</span>
            )}
          </div>

          {product.shortDescription && <p className="mb-4 text-sm text-secondary">{product.shortDescription}</p>}

          {Object.entries(attributeGroups).map(([name, values]) => (
            <div key={name} className="mb-4">
              <h3 className="mb-1.5 text-sm font-medium text-dark">{name}</h3>
              <div className="flex flex-wrap gap-2">
                {values.map((value) => (
                  <button
                    key={value}
                    type="button"
                    onClick={() => handleSelectAttribute(name, value)}
                    className={`rounded border px-3 py-1.5 text-sm ${
                      selectedAttributes[name] === value ? 'border-primary bg-primary text-white' : 'border-gray-300 text-dark'
                    }`}
                  >
                    {value}
                  </button>
                ))}
              </div>
            </div>
          ))}

          {isVariable && allAttributesSelected && !selectedVariation && (
            <p className="mb-4 text-sm text-red-600">Esa combinación no está disponible.</p>
          )}

          <div className="mb-4 flex items-center gap-3">
            <span className="text-sm text-dark">Cantidad</span>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setQuantity((q) => Math.max(1, q - 1))}
                className="h-8 w-8 rounded border border-gray-300"
              >
                −
              </button>
              <span className="w-8 text-center">{quantity}</span>
              <button
                type="button"
                onClick={() => setQuantity((q) => Math.min(stock || q + 1, q + 1))}
                className="h-8 w-8 rounded border border-gray-300"
              >
                +
              </button>
            </div>
            <span className="text-xs text-secondary">{stock > 0 ? `${stock} disponibles` : 'Sin stock'}</span>
          </div>

          <div className="flex gap-3">
            <button
              type="button"
              disabled={!canAddToCart}
              onClick={handleAddToCart}
              className="flex-1 rounded bg-primary px-4 py-2.5 text-sm font-medium text-white disabled:opacity-40"
            >
              Agregar al Carrito
            </button>
            <button
              type="button"
              disabled={!canAddToCart}
              onClick={handleBuyNow}
              className="flex-1 rounded border border-primary px-4 py-2.5 text-sm font-medium text-primary disabled:opacity-40"
            >
              Comprar Ahora
            </button>
          </div>

          {product.sku && <p className="mt-4 text-xs text-secondary">SKU: {product.sku}</p>}
        </div>
      </div>

      {product.description && (
        <div className="mt-10 border-t border-gray-100 pt-6">
          <h2 className="mb-2 text-lg font-semibold text-dark">Descripción</h2>
          <p className="whitespace-pre-line text-sm text-secondary">{product.description}</p>
        </div>
      )}

      {related.length > 0 && (
        <div className="mt-10 border-t border-gray-100 pt-6">
          <h2 className="mb-4 text-lg font-semibold text-dark">Productos Relacionados</h2>
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            {related.map((item) => (
              <ProductCard key={item.id} product={item} />
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
