import { Link } from 'react-router-dom'
import { useCartStore } from '../stores/useCartStore'
import { useNotificationStore } from '../stores/useNotificationStore'
import type { Product } from '../types/api'

export default function ProductCard({ product }: { product: Product }) {
  const addItem = useCartStore((state) => state.addItem)
  const notify = useNotificationStore((state) => state.notify)
  const isVariable = product.productType === 'variable'

  function handleAddToCart() {
    addItem(product, 1, null)
    notify(`${product.name} agregado al carrito`, 'success')
  }

  return (
    <div className="group relative flex flex-col overflow-hidden rounded-lg border border-gray-400 bg-white">
      {/* GAP-19. La imagen repite el enlace del nombre: fuera del Tab y del lector de pantalla, que leía el
          producto dos veces (y sin foto, un enlace sin nombre). Con el mouse se sigue pudiendo hacer clic. */}
      <Link
        to={`/product/${product.slug}`}
        tabIndex={-1}
        aria-hidden="true"
        className="relative block aspect-square overflow-hidden bg-gray-100"
      >
        {product.featuredImage && (
          <img
            src={product.featuredImage}
            alt=""
            loading="lazy"
            className="h-full w-full object-cover transition-transform group-hover:scale-105"
          />
        )}

        <div className="absolute left-2 top-2 flex flex-col gap-1">
          {product.featured && (
            // GAP-19. Texto oscuro: el blanco sobre el acento por defecto (#FFD700) daba 1,4:1.
            <span className="rounded bg-accent px-2 py-0.5 text-xs font-semibold text-dark">Destacado</span>
          )}
          {!product.inStock && (
            <span className="rounded bg-gray-700 px-2 py-0.5 text-xs font-semibold text-white">Agotado</span>
          )}
        </div>
      </Link>

      <div className="flex flex-1 flex-col gap-1 p-3">
        {product.category && <span className="text-xs text-secondary">{product.category.name}</span>}
        <Link to={`/product/${product.slug}`} className="line-clamp-2 text-sm font-medium text-dark hover:text-primary">
          {product.name}
        </Link>
        {product.shortDescription && (
          <p className="line-clamp-2 text-xs text-secondary">{product.shortDescription}</p>
        )}

        <div className="mt-auto flex items-center justify-between pt-2">
          <span className="font-semibold text-dark">{product.formattedPrice}</span>

          {!product.inStock ? (
            <span className="text-xs text-secondary">Sin stock</span>
          ) : isVariable ? (
            <Link
              to={`/product/${product.slug}`}
              className="rounded bg-primary px-3 py-1.5 text-xs font-medium text-white hover:opacity-90"
            >
              Ver opciones<span className="sr-only"> de {product.name}</span>
            </Link>
          ) : (
            <button
              type="button"
              onClick={handleAddToCart}
              // Con una grilla de doce «Agregar» iguales, el lector de pantalla no decía a qué producto.
              aria-label={`Agregar ${product.name} al carrito`}
              className="rounded bg-primary px-3 py-1.5 text-xs font-medium text-white hover:opacity-90"
            >
              Agregar
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
