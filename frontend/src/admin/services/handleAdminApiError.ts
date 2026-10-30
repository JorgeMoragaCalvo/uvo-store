import { useAdminAuthStore } from '@/admin/stores/useAdminAuthStore'
import { NETWORK_ERROR_MESSAGE } from '@/services/errors'

interface AxiosLikeError {
  response?: { status?: number; data?: { message?: string } }
  message?: string
}

/**
 * F19. Qué hace el panel con un error de la API.
 *
 * <p>Era una lambda anónima dentro del interceptor de `adminApi`, y por eso no había forma de probarla:
 * todas las pruebas del panel simulan el módulo `adminApi` completo y ninguna llega al interceptor.
 *
 * La regla, y las dos mitades importan:
 *
 * - **401** = no hay sesión válida (token ausente, caducado, revocado o de otra tienda). Se cierra sesión
 *   y `RequireAdminAuth` lleva al login. Antes el backend respondía **403** en estos casos y esta rama
 *   nunca se tomaba: la SPA se quedaba con un token muerto, el guarda no se disparaba porque seguía
 *   habiendo token, y el administrador veía errores en todas las pantallas sin volver nunca al login.
 * - **403** = hay sesión, pero esto no le toca. **No se cierra sesión**, y no es un descuido: un
 *   administrador restringido que abre una sección que no le corresponde recibe un 403 legítimo
 *   (`@PreAuthorize`), y echarlo al login lo dejaría en un bucle.
 */
export function handleAdminApiError(error: AxiosLikeError): Promise<never> {
  if (error.response?.status === 401) {
    const { logout, markSessionExpired } = useAdminAuthStore.getState()
    // Se marca antes de limpiar: el login lo lee para explicar por qué apareció ahí. Sin esto, la
    // expulsión —que ahora sí ocurre— deja al administrador en la pantalla de login a mitad de una
    // tarea sin ninguna señal de qué pasó, que se parece bastante a un fallo de la aplicación.
    markSessionExpired()
    logout()
  }
  const data = error.response?.data
  // Un cuerpo que no es JSON (el HTML de un proxy) no se esparce: serían sus caracteres uno por uno.
  const body = data && typeof data === 'object' ? data : {}
  return Promise.reject({ ...body, status: error.response?.status, message: adminMessage(error) })
}

// PROD-24b. Qué texto llega a los `toast.error(message ?? 'No se pudo…')` de las pantallas.
//
// Antes era `data?.message ?? error.message`, y el segundo término siempre existe: sin respuesta, axios
// pone "Network Error" o "timeout of …", y con una respuesta que no es del backend (un 502 del proxy, en
// HTML), "Request failed with status code 502". Así el respaldo en español de cada pantalla casi nunca se
// veía. Ahora:
// - sin respuesta → un texto de red en español;
// - 4xx de negocio, permisos y validación → el mensaje del backend, que está pensado para el usuario;
// - 404 y 5xx → nada, para que cada pantalla diga en contexto qué no se pudo hacer. El 404 trae el texto de
//   la excepción de Java ("Order 42 not found") y el 500, un genérico que no dice qué falló.
function adminMessage(error: AxiosLikeError): string | undefined {
  if (!error.response) return NETWORK_ERROR_MESSAGE
  const status = error.response.status ?? 0
  if (status === 404 || status >= 500) return undefined
  const data = error.response.data
  return data && typeof data === 'object' ? data.message : undefined
}
