import { Link } from 'react-router-dom'
import { useStoreSettingsStore } from '../../stores/useStoreSettingsStore'

export default function Footer() {
  const { settings, categories } = useStoreSettingsStore()

  const socials = [
    { url: settings?.facebookUrl, label: 'Facebook' },
    { url: settings?.instagramUrl, label: 'Instagram' },
    { url: settings?.twitterUrl, label: 'Twitter' },
    { url: settings?.tiktokUrl, label: 'TikTok' },
  ].filter((social) => social.url)

  return (
    <footer className="mt-auto bg-dark text-gray-300">
      <div className="mx-auto grid max-w-6xl gap-8 px-4 py-10 sm:grid-cols-2 lg:grid-cols-4">
        <div>
          <h3 className="mb-3 text-lg font-semibold text-white">{settings?.storeName ?? 'UvoStore'}</h3>
          {settings?.storeDescription && <p className="text-sm">{settings.storeDescription}</p>}
        </div>

        <div>
          <h4 className="mb-3 font-semibold text-white">Categorías</h4>
          <ul className="space-y-2 text-sm">
            {categories.slice(0, 6).map((category) => (
              <li key={category.id}>
                <Link to={`/category/${category.slug}`} className="hover:text-white">
                  {category.name}
                </Link>
              </li>
            ))}
          </ul>
        </div>

        <div>
          <h4 className="mb-3 font-semibold text-white">Legal</h4>
          <ul className="space-y-2 text-sm">
            <li><Link to="/terminos-y-condiciones" className="hover:text-white">Términos y Condiciones</Link></li>
            <li><Link to="/politica-de-privacidad" className="hover:text-white">Política de Privacidad</Link></li>
            <li><Link to="/politica-de-envios" className="hover:text-white">Política de Envíos</Link></li>
            <li><Link to="/politica-de-devoluciones" className="hover:text-white">Política de Devoluciones</Link></li>
            <li><Link to="/track-order" className="hover:text-white">Seguimiento de Pedido</Link></li>
          </ul>
        </div>

        <div>
          <h4 className="mb-3 font-semibold text-white">Contacto</h4>
          <ul className="space-y-2 text-sm">
            {settings?.contactEmail && <li>{settings.contactEmail}</li>}
            {settings?.contactPhone && <li>{settings.contactPhone}</li>}
            {settings?.whatsappNumber && <li>WhatsApp: {settings.whatsappNumber}</li>}
          </ul>
          {socials.length > 0 && (
            <ul className="mt-4 flex gap-3 text-sm">
              {socials.map((social) => (
                <li key={social.label}>
                  <a href={social.url!} target="_blank" rel="noreferrer" className="hover:text-white">
                    {social.label}
                  </a>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      <div className="border-t border-white/10 py-4 text-center text-xs">
        © {new Date().getFullYear()} {settings?.storeName ?? 'UvoStore'}. Todos los derechos reservados.
      </div>
    </footer>
  )
}
