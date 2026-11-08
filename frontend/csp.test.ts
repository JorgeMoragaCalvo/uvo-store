import { describe, expect, it } from 'vitest'
import type { HtmlTagDescriptor, IndexHtmlTransformHook } from 'vite'
import { buildContentSecurityPolicy, contentSecurityPolicyPlugin } from './csp.ts'

function directive(policy: string, name: string): string {
  return policy.split('; ').find((part) => part.startsWith(`${name} `)) ?? ''
}

// GAP-13. La CSP es lo que impide que un script inyectado lea el token del panel de localStorage.
describe('buildContentSecurityPolicy', () => {
  it('only runs the app own scripts: no inline code and no eval', () => {
    const scripts = directive(buildContentSecurityPolicy({}), 'script-src')

    expect(scripts).toBe("script-src 'self'")
  })

  it('without a Sentry DSN, data can only go back to the same origin', () => {
    expect(directive(buildContentSecurityPolicy({}), 'connect-src')).toBe("connect-src 'self'")
    expect(directive(buildContentSecurityPolicy({ sentryDsn: '   ' }), 'connect-src')).toBe("connect-src 'self'")
  })

  it('with a Sentry DSN, allows its origin only, without the key', () => {
    const policy = buildContentSecurityPolicy({ sentryDsn: 'https://clave123@o42.ingest.sentry.io/7' })

    expect(directive(policy, 'connect-src')).toBe("connect-src 'self' https://o42.ingest.sentry.io")
    expect(policy).not.toContain('clave123')
  })

  it('ignores a malformed DSN instead of breaking the policy', () => {
    expect(directive(buildContentSecurityPolicy({ sentryDsn: 'no es una url' }), 'connect-src')).toBe("connect-src 'self'")
  })

  it('lets the checkout post the Webpay form to Transbank, integration and production', () => {
    const forms = directive(buildContentSecurityPolicy({}), 'form-action')

    expect(forms).toContain('https://webpay3g.transbank.cl')
    expect(forms).toContain('https://webpay3gint.transbank.cl')
  })

  it('closes base and plugin injection', () => {
    const policy = buildContentSecurityPolicy({})

    expect(policy).toContain("base-uri 'self'")
    expect(policy).toContain("object-src 'none'")
  })
})

describe('contentSecurityPolicyPlugin', () => {
  it('runs only in the build: the dev server needs an inline script', () => {
    expect(contentSecurityPolicyPlugin({}).apply).toBe('build')
  })

  it('puts the CSP and the referrer policy first in the head, before any script', () => {
    const transform = contentSecurityPolicyPlugin({}).transformIndexHtml as IndexHtmlTransformHook
    const tags = transform.call(
      {} as ThisParameterType<IndexHtmlTransformHook>,
      '',
      {} as Parameters<IndexHtmlTransformHook>[1],
    ) as HtmlTagDescriptor[]

    expect(tags).toEqual([
      expect.objectContaining({
        tag: 'meta',
        attrs: { 'http-equiv': 'Content-Security-Policy', content: buildContentSecurityPolicy({}) },
        injectTo: 'head-prepend',
      }),
      expect.objectContaining({
        tag: 'meta',
        attrs: { name: 'referrer', content: 'strict-origin-when-cross-origin' },
        injectTo: 'head-prepend',
      }),
    ])
  })
})
