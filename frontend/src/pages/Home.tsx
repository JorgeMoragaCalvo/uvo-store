import { Link } from 'react-router-dom'
import { useStoreSettingsStore } from '../stores/useStoreSettingsStore'
import HeroSlider from '../components/home/HeroSlider'
import ProductSection from '../components/home/ProductSection'

const BENEFIT_ICONS: Record<string, string> = {
  truck: '🚚',
  'shield-check': '🛡️',
  refresh: '🔄',
  headset: '🎧',
}

export default function Home() {
  const { settings, banners, categories } = useStoreSettingsStore()

  if (!settings) return null

  const benefits = [
    { icon: settings.benefit1Icon, title: settings.benefit1Title, description: settings.benefit1Description },
    { icon: settings.benefit2Icon, title: settings.benefit2Title, description: settings.benefit2Description },
    { icon: settings.benefit3Icon, title: settings.benefit3Title, description: settings.benefit3Description },
    { icon: settings.benefit4Icon, title: settings.benefit4Title, description: settings.benefit4Description },
  ].filter((b) => b.title)

  return (
    <div>
      {settings.showHero && <HeroSlider banners={banners} autoplaySpeed={settings.heroAutoplaySpeed} />}

      {settings.showCategories && categories.length > 0 && (
        <section className="mx-auto max-w-6xl px-4 py-10">
          <h2 className="mb-5 text-xl font-semibold text-dark">{settings.categoriesTitle || 'Categorías'}</h2>
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-6">
            {categories.slice(0, settings.categoriesLimit).map((category) => (
              <Link
                key={category.id}
                to={`/category/${category.slug}`}
                className="flex flex-col items-center gap-2 rounded-lg border border-gray-100 p-4 text-center hover:border-primary"
              >
                <div className="flex h-16 w-16 items-center justify-center overflow-hidden rounded-full bg-gray-100">
                  {category.image && <img src={category.image} alt={category.name} className="h-full w-full object-cover" />}
                </div>
                <span className="text-sm font-medium text-dark">{category.name}</span>
              </Link>
            ))}
          </div>
        </section>
      )}

      {settings.showNewProducts && (
        <ProductSection
          title={settings.newProductsTitle || 'Nuevos Productos'}
          limit={settings.newProductsLimit}
          filter={{ is_new: true }}
        />
      )}

      {settings.showFeaturedProducts && (
        <ProductSection
          title={settings.featuredProductsTitle || 'Productos Destacados'}
          limit={settings.featuredProductsLimit}
          filter={{ featured: true }}
        />
      )}

      {settings.showDeals && (
        <ProductSection
          title={settings.dealsTitle || 'Ofertas'}
          limit={settings.dealsLimit}
          filter={{ on_sale: true }}
          tone="deals"
        />
      )}

      {settings.showBenefits && benefits.length > 0 && (
        <section className="mx-auto max-w-6xl px-4 py-10">
          <div className="grid grid-cols-2 gap-6 sm:grid-cols-4">
            {benefits.map((benefit) => (
              <div key={benefit.title} className="flex flex-col items-center gap-2 text-center">
                <span className="text-3xl">{BENEFIT_ICONS[benefit.icon ?? ''] ?? '✔️'}</span>
                <span className="text-sm font-semibold text-dark">{benefit.title}</span>
                {benefit.description && <span className="text-xs text-secondary">{benefit.description}</span>}
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  )
}
