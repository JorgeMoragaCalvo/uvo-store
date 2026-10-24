import axios from 'axios'

// POS-6. Sin contraseña: el dueño recibe una invitación por correo y la elige él.
export interface StoreOnboardingRequest {
  slug: string
  domain: string
  storeName: string
  adminName: string
  adminEmail: string
}

export interface StoreOnboardingResponse {
  storeId: number
  storeName: string
  slug: string
  domain: string | null
  adminUserId: number
  adminEmail: string
  // PROD-04. `domainVerifiedAt` en null significa que el dominio propio está configurado pero todavía no
  // comprobado, y por tanto que los enlaces que salen (correos, retornos de pasarela) usan el subdominio.
  // Las dos URLs las compone el backend: son las direcciones que hay que entregarle al cliente.
  domainVerifiedAt: string | null
  storefrontUrl: string
  adminUrl: string
  // POS-6. false cuando UvoPOS reintenta un alta que ya existía. Desde este formulario siempre es true.
  created: boolean
}

const client = axios.create({
  baseURL: `${window.location.origin}/api/platform`,
  headers: { Accept: 'application/json' },
})

client.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const data = error.response?.data
    // POS-6: el estado viaja aparte para distinguir un 404, que aquí significa "fuera de la red privada".
    return Promise.reject({ status: error.response?.status, message: data?.message ?? error.message, ...data })
  },
)

export const platformApi = {
  createStore: (platformKey: string, request: StoreOnboardingRequest): Promise<StoreOnboardingResponse> =>
    client.post('/stores', request, { headers: { 'X-Platform-Key': platformKey } }),
}

export default platformApi
