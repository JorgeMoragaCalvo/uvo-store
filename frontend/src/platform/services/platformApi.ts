import axios from 'axios'

export interface StoreOnboardingRequest {
  slug: string
  domain: string
  storeName: string
  adminName: string
  adminEmail: string
  adminPassword: string
}

export interface StoreOnboardingResponse {
  storeId: number
  storeName: string
  slug: string
  domain: string | null
  adminUserId: number
  adminEmail: string
}

const client = axios.create({
  baseURL: `${window.location.origin}/api/platform`,
  headers: { Accept: 'application/json' },
})

client.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const data = error.response?.data
    return Promise.reject({ message: data?.message ?? error.message, ...data })
  },
)

export const platformApi = {
  createStore: (platformKey: string, request: StoreOnboardingRequest): Promise<StoreOnboardingResponse> =>
    client.post('/stores', request, { headers: { 'X-Platform-Key': platformKey } }),
}

export default platformApi
