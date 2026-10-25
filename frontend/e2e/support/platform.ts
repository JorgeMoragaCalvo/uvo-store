// PROD-14a. El alta de una tienda por la API de plataforma, como lo hará el backoffice del POS (POS-6).

import { http, type HttpResult } from './http.ts'
import { BACKEND_URL, E2E_PLATFORM_API_KEY } from './env.ts'
import type { PosCredentials } from './pos.ts'

export interface StoreOnboarding {
  slug: string
  storeName: string
  adminName: string
  adminEmail: string
  pos?: PosCredentials & { companyName: string; apiUrl: string }
}

export function createStoreViaApi(onboarding: StoreOnboarding): Promise<HttpResult> {
  const { pos, ...store } = onboarding
  return http('POST', `${BACKEND_URL}/api/platform/stores`, {
    headers: { 'X-Platform-Key': E2E_PLATFORM_API_KEY, 'X-Platform-Operator': 'e2e' },
    body: {
      ...store,
      domain: '',
      pos: pos && {
        companyId: pos.companyId,
        companyName: pos.companyName,
        apiUrl: pos.apiUrl,
        apiKey: pos.apiKey,
        webhookSecret: pos.webhookSecret,
      },
    },
  })
}
