import type { Breadcrumb, ErrorEvent } from '@sentry/react'
import { describe, expect, it } from 'vitest'
import {
  FILTERED,
  buildSentryOptions,
  scrubBreadcrumb,
  scrubEvent,
  scrubUrl,
  type SentryEnv,
} from './sentry'

const DSN = 'https://clave@sentry.example/1'

describe('scrubUrl', () => {
  it('oculta el token de las rutas de correo, que es un secreto de un solo uso', () => {
    expect(scrubUrl('https://demo.uvostore.cl/admin/reset-password?token=abc123')).toBe(
      `https://demo.uvostore.cl/admin/reset-password?token=${FILTERED}`,
    )
    expect(scrubUrl('/admin/aceptar-invitacion?token=abc123')).toBe(
      `/admin/aceptar-invitacion?token=${FILTERED}`,
    )
  })

  it('conserva el resto de parámetros, en su orden', () => {
    expect(scrubUrl('/admin/reset-password?lang=es&token=abc&x=1')).toBe(
      `/admin/reset-password?lang=es&token=${FILTERED}&x=1`,
    )
  })

  it('reconoce el nombre por patrón, no solo "token", y sin importar las mayúsculas', () => {
    expect(scrubUrl('/x?invitation_token=a&Reset-Token=b&password=c&client_secret=d&signature=e')).toBe(
      `/x?invitation_token=${FILTERED}&Reset-Token=${FILTERED}&password=${FILTERED}&client_secret=${FILTERED}&signature=${FILTERED}`,
    )
  })

  it('reconoce el nombre aunque venga codificado', () => {
    expect(scrubUrl('/x?%74oken=abc')).toBe(`/x?%74oken=${FILTERED}`)
  })

  it('no toca el fragmento ni una URL sin query', () => {
    expect(scrubUrl('/admin/reset-password?token=abc#seccion')).toBe(
      `/admin/reset-password?token=${FILTERED}#seccion`,
    )
    expect(scrubUrl('/admin/login')).toBe('/admin/login')
    expect(scrubUrl('/productos?q=token-de-regalo')).toBe('/productos?q=token-de-regalo')
  })

  it('un nombre sensible sin valor no tiene nada que ocultar y no se rompe', () => {
    expect(scrubUrl('/x?token')).toBe('/x?token')
    expect(scrubUrl('/x?a=%E0%A4%A&token=abc')).toBe(`/x?a=%E0%A4%A&token=${FILTERED}`)
  })
})

describe('scrubEvent', () => {
  it('limpia la URL de la página, la query y el Referer', () => {
    const event = {
      type: undefined,
      request: {
        url: 'https://demo.uvostore.cl/admin/reset-password?token=abc',
        query_string: 'token=abc&lang=es',
        headers: { Referer: 'https://demo.uvostore.cl/admin/aceptar-invitacion?token=def', 'User-Agent': 'x' },
      },
    } as ErrorEvent

    const result = scrubEvent(event)

    expect(result.request?.url).toBe(`https://demo.uvostore.cl/admin/reset-password?token=${FILTERED}`)
    expect(result.request?.query_string).toBe(`token=${FILTERED}&lang=es`)
    expect(result.request?.headers?.Referer).toBe(
      `https://demo.uvostore.cl/admin/aceptar-invitacion?token=${FILTERED}`,
    )
    expect(result.request?.headers?.['User-Agent']).toBe('x')
  })

  it('deja pasar un evento sin datos de la petición', () => {
    const event = { type: undefined, message: 'algo' } as ErrorEvent
    expect(scrubEvent(event)).toBe(event)
  })
})

describe('scrubBreadcrumb', () => {
  it('limpia las direcciones de navegación', () => {
    const breadcrumb: Breadcrumb = {
      category: 'navigation',
      data: { from: '/admin/login', to: '/admin/reset-password?token=abc' },
    }
    expect(scrubBreadcrumb(breadcrumb).data).toEqual({
      from: '/admin/login',
      to: `/admin/reset-password?token=${FILTERED}`,
    })
  })

  it('limpia la dirección de las peticiones de red', () => {
    const breadcrumb: Breadcrumb = {
      category: 'fetch',
      data: { url: '/api/v1/algo?token=abc', method: 'GET', status_code: 200 },
    }
    expect(scrubBreadcrumb(breadcrumb).data).toEqual({
      url: `/api/v1/algo?token=${FILTERED}`,
      method: 'GET',
      status_code: 200,
    })
  })

  it('deja pasar un breadcrumb sin datos', () => {
    const breadcrumb: Breadcrumb = { category: 'console', message: 'hola' }
    expect(scrubBreadcrumb(breadcrumb)).toBe(breadcrumb)
  })
})

describe('buildSentryOptions', () => {
  const base: SentryEnv = { MODE: 'production' }

  it('sin DSN, o con el DSN en blanco, Sentry queda apagado', () => {
    expect(buildSentryOptions(base)).toBeNull()
    expect(buildSentryOptions({ ...base, VITE_SENTRY_DSN: '' })).toBeNull()
    expect(buildSentryOptions({ ...base, VITE_SENTRY_DSN: '   ' })).toBeNull()
  })

  it('toma el ambiente y la versión de las variables de build', () => {
    const options = buildSentryOptions({
      ...base,
      VITE_SENTRY_DSN: DSN,
      VITE_SENTRY_ENVIRONMENT: 'staging',
      VITE_SENTRY_RELEASE: 'v1.2.3',
    })
    expect(options).toMatchObject({ dsn: DSN, environment: 'staging', release: 'v1.2.3' })
  })

  it('sin ambiente cae al MODE de Vite, y sin versión no inventa ninguna', () => {
    expect(buildSentryOptions({ VITE_SENTRY_DSN: DSN, MODE: 'development' })?.environment).toBe('development')
    const options = buildSentryOptions({
      VITE_SENTRY_DSN: DSN,
      VITE_SENTRY_ENVIRONMENT: '',
      VITE_SENTRY_RELEASE: ' ',
      MODE: 'production',
    })
    expect(options?.environment).toBe('production')
    expect(options?.release).toBeUndefined()
  })

  it('instala el filtrado de eventos y de breadcrumbs', () => {
    const options = buildSentryOptions({ ...base, VITE_SENTRY_DSN: DSN })
    expect(options?.beforeSend).toBe(scrubEvent)
    expect(options?.beforeBreadcrumb).toBe(scrubBreadcrumb)
  })
})
