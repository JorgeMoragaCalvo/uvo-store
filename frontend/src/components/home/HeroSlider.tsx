import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type { HomeBanner } from '../../types/api'

const TEXT_POSITION_CLASS: Record<HomeBanner['textPosition'], string> = {
  left: 'items-start text-left',
  center: 'items-center text-center',
  right: 'items-end text-right',
}

function prefersReducedMotion() {
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true
}

// Un enlace interno con <a href> recargaba la SPA entera; uno externo sigue siendo <a>.
function BannerLink({ to, newTab, className, children }: { to: string; newTab: boolean; className: string; children: ReactNode }) {
  if (to.startsWith('/') && !to.startsWith('//') && !newTab) {
    return (
      <Link to={to} className={className}>
        {children}
      </Link>
    )
  }
  return (
    <a href={to} target={newTab ? '_blank' : undefined} rel={newTab ? 'noreferrer' : undefined} className={className}>
      {children}
    </a>
  )
}

export default function HeroSlider({ banners, autoplaySpeed }: { banners: HomeBanner[]; autoplaySpeed: number }) {
  const [index, setIndex] = useState(0)
  // PROD-24b (WCAG 2.2.2). Lo que se mueve solo tiene que poder detenerse: un botón de pausa, y además
  // se detiene mientras el puntero o el foco están encima, para que nadie pierda el texto a mitad de
  // lectura. Quien pidió menos movimiento al sistema no lo ve rotar.
  const [paused, setPaused] = useState(prefersReducedMotion)
  const [hovered, setHovered] = useState(false)
  const [focused, setFocused] = useState(false)

  const canRotate = banners.length > 1 && Boolean(autoplaySpeed)
  const rotating = canRotate && !paused && !hovered && !focused

  useEffect(() => {
    if (!rotating) return
    const timer = setInterval(() => setIndex((i) => (i + 1) % banners.length), autoplaySpeed)
    return () => clearInterval(timer)
  }, [rotating, banners.length, autoplaySpeed])

  if (banners.length === 0) return null

  const banner = banners[index]

  return (
    // En el celular, 16/7 daba unos 157 px de alto y cortaba el título, la descripción y el botón.
    <section
      aria-roledescription="carrusel"
      aria-label="Destacados"
      className="relative aspect-[4/3] w-full overflow-hidden bg-gray-100 sm:aspect-[16/7]"
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      onFocus={() => setFocused(true)}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setFocused(false)
      }}
    >
      <div
        role="group"
        aria-roledescription="diapositiva"
        aria-label={`${index + 1} de ${banners.length}`}
        // Mientras rota solo, anunciar cada cambio sería ruido; detenido, sí se anuncia lo que se eligió.
        aria-live={rotating ? 'off' : 'polite'}
        className="absolute inset-0"
      >
        {/* El título ya se lee como encabezado: repetido en el alt se oía dos veces. */}
        <img src={banner.image} alt="" loading="eager" className="absolute inset-0 h-full w-full object-cover" />
        <div
          className="absolute inset-0"
          style={{ backgroundColor: banner.overlayColor ?? '#000000', opacity: (banner.overlayOpacity ?? 0) / 100 }}
        />

        <div className={`relative flex h-full flex-col justify-center gap-3 px-12 py-6 sm:p-8 ${TEXT_POSITION_CLASS[banner.textPosition]} ${banner.textColor === 'light' ? 'text-white' : 'text-dark'}`}>
          {banner.subtitle && <span className="text-sm font-medium uppercase tracking-wide">{banner.subtitle}</span>}
          {banner.title && <h1 className="text-2xl font-bold sm:text-3xl md:text-4xl">{banner.title}</h1>}
          {banner.description && <p className="max-w-md text-sm">{banner.description}</p>}

          <div className="mt-2 flex flex-wrap gap-3">
            {banner.ctaText && banner.ctaLink && (
              <BannerLink to={banner.ctaLink} newTab={banner.ctaNewTab} className="rounded bg-primary px-5 py-2.5 text-sm font-semibold text-white">
                {banner.ctaText}
              </BannerLink>
            )}
            {banner.ctaSecondaryText && banner.ctaSecondaryLink && (
              <BannerLink to={banner.ctaSecondaryLink} newTab={false} className="rounded border border-current px-5 py-2.5 text-sm font-semibold">
                {banner.ctaSecondaryText}
              </BannerLink>
            )}
          </div>
        </div>
      </div>

      {banners.length > 1 && (
        <>
          <button
            type="button"
            onClick={() => setIndex((i) => (i - 1 + banners.length) % banners.length)}
            aria-label="Diapositiva anterior"
            className="absolute left-3 top-1/2 flex size-9 -translate-y-1/2 items-center justify-center rounded-full bg-white/80 text-sm text-dark"
          >
            <span aria-hidden="true">‹</span>
          </button>
          <button
            type="button"
            onClick={() => setIndex((i) => (i + 1) % banners.length)}
            aria-label="Diapositiva siguiente"
            className="absolute right-3 top-1/2 flex size-9 -translate-y-1/2 items-center justify-center rounded-full bg-white/80 text-sm text-dark"
          >
            <span aria-hidden="true">›</span>
          </button>
          <div className="absolute bottom-1 left-1/2 flex -translate-x-1/2 items-center">
            {canRotate && (
              <button
                type="button"
                onClick={() => setPaused((p) => !p)}
                aria-label={paused ? 'Reanudar el carrusel' : 'Pausar el carrusel'}
                className="mr-1 flex size-6 items-center justify-center rounded-full bg-white/80 text-[10px] text-dark"
              >
                <span aria-hidden="true">{paused ? '▶' : '❚❚'}</span>
              </button>
            )}
            {/* Cada punto mide 8 px, pero el botón que lo contiene, 24: el mínimo para tocarlo con el dedo. */}
            {banners.map((b, i) => (
              <button
                key={b.id}
                type="button"
                onClick={() => setIndex(i)}
                aria-label={`Ir a la diapositiva ${i + 1}`}
                aria-current={i === index ? 'true' : undefined}
                className="flex size-6 items-center justify-center"
              >
                <span className={`block h-2 w-2 rounded-full ${i === index ? 'bg-white' : 'bg-white/50'}`} />
              </button>
            ))}
          </div>
        </>
      )}
    </section>
  )
}
