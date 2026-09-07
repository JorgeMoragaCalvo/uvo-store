import { useEffect, useState } from 'react'
import type { HomeBanner } from '../../types/api'

const TEXT_POSITION_CLASS: Record<HomeBanner['textPosition'], string> = {
  left: 'items-start text-left',
  center: 'items-center text-center',
  right: 'items-end text-right',
}

export default function HeroSlider({ banners, autoplaySpeed }: { banners: HomeBanner[]; autoplaySpeed: number }) {
  const [index, setIndex] = useState(0)

  useEffect(() => {
    if (banners.length <= 1 || !autoplaySpeed) return
    const timer = setInterval(() => setIndex((i) => (i + 1) % banners.length), autoplaySpeed)
    return () => clearInterval(timer)
  }, [banners.length, autoplaySpeed])

  if (banners.length === 0) return null

  const banner = banners[index]

  return (
    <div className="relative aspect-[16/7] w-full overflow-hidden bg-gray-100">
      <img src={banner.image} alt={banner.title ?? ''} loading="eager" className="absolute inset-0 h-full w-full object-cover" />
      <div
        className="absolute inset-0"
        style={{ backgroundColor: banner.overlayColor ?? '#000000', opacity: (banner.overlayOpacity ?? 0) / 100 }}
      />

      <div className={`relative flex h-full flex-col justify-center gap-3 p-8 ${TEXT_POSITION_CLASS[banner.textPosition]} ${banner.textColor === 'light' ? 'text-white' : 'text-dark'}`}>
        {banner.subtitle && <span className="text-sm font-medium uppercase tracking-wide">{banner.subtitle}</span>}
        {banner.title && <h1 className="text-3xl font-bold sm:text-4xl">{banner.title}</h1>}
        {banner.description && <p className="max-w-md text-sm">{banner.description}</p>}

        <div className="mt-2 flex gap-3">
          {banner.ctaText && banner.ctaLink && (
            <a
              href={banner.ctaLink}
              target={banner.ctaNewTab ? '_blank' : undefined}
              rel={banner.ctaNewTab ? 'noreferrer' : undefined}
              className="rounded bg-primary px-5 py-2.5 text-sm font-semibold text-white"
            >
              {banner.ctaText}
            </a>
          )}
          {banner.ctaSecondaryText && banner.ctaSecondaryLink && (
            <a href={banner.ctaSecondaryLink} className="rounded border border-current px-5 py-2.5 text-sm font-semibold">
              {banner.ctaSecondaryText}
            </a>
          )}
        </div>
      </div>

      {banners.length > 1 && (
        <>
          <button
            type="button"
            onClick={() => setIndex((i) => (i - 1 + banners.length) % banners.length)}
            className="absolute left-3 top-1/2 -translate-y-1/2 rounded-full bg-white/80 px-3 py-1.5 text-sm"
          >
            ‹
          </button>
          <button
            type="button"
            onClick={() => setIndex((i) => (i + 1) % banners.length)}
            className="absolute right-3 top-1/2 -translate-y-1/2 rounded-full bg-white/80 px-3 py-1.5 text-sm"
          >
            ›
          </button>
          <div className="absolute bottom-3 left-1/2 flex -translate-x-1/2 gap-2">
            {banners.map((b, i) => (
              <button
                key={b.id}
                type="button"
                onClick={() => setIndex(i)}
                className={`h-2 w-2 rounded-full ${i === index ? 'bg-white' : 'bg-white/50'}`}
              />
            ))}
          </div>
        </>
      )}
    </div>
  )
}
