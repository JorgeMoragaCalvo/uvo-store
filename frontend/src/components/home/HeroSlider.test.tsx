import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import HeroSlider from './HeroSlider'
import type { HomeBanner } from '@/types/api'

function banner(id: number, title: string): HomeBanner {
  return {
    id,
    title,
    subtitle: null,
    description: null,
    image: `/banner-${id}.jpg`,
    mobileImage: null,
    ctaText: null,
    ctaLink: null,
    ctaNewTab: false,
    ctaSecondaryText: null,
    ctaSecondaryLink: null,
    textPosition: 'left',
    textColor: 'light',
    overlayColor: null,
    overlayOpacity: 0,
  } as unknown as HomeBanner
}

const banners = [banner(1, 'Primera'), banner(2, 'Segunda')]

function renderSlider() {
  return render(
    <MemoryRouter>
      <HeroSlider banners={banners} autoplaySpeed={1000} />
    </MemoryRouter>,
  )
}

// PROD-24b (WCAG 2.2.2). Lo que rota solo tiene que poder detenerse.
describe('HeroSlider', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('rotates on its own', () => {
    renderSlider()
    expect(screen.getByRole('heading', { name: 'Primera' })).toBeInTheDocument()

    act(() => vi.advanceTimersByTime(1000))

    expect(screen.getByRole('heading', { name: 'Segunda' })).toBeInTheDocument()
  })

  it('stops rotating when paused', () => {
    renderSlider()

    // fireEvent no mueve el foco (que también detiene la rotación): así se prueba solo la pausa.
    fireEvent.click(screen.getByRole('button', { name: 'Pausar el carrusel' }))
    act(() => vi.advanceTimersByTime(3000))

    expect(screen.getByRole('heading', { name: 'Primera' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reanudar el carrusel' })).toBeInTheDocument()
  })

  it('stops rotating while the pointer is over it', () => {
    renderSlider()

    fireEvent.mouseEnter(screen.getByRole('region', { name: 'Destacados' }))
    act(() => vi.advanceTimersByTime(3000))

    expect(screen.getByRole('heading', { name: 'Primera' })).toBeInTheDocument()
  })

  it('names its controls and marks the current slide', () => {
    renderSlider()

    expect(screen.getByRole('button', { name: 'Diapositiva siguiente' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ir a la diapositiva 1' })).toHaveAttribute('aria-current', 'true')
    expect(screen.getByRole('button', { name: 'Ir a la diapositiva 2' })).not.toHaveAttribute('aria-current')
  })
})
