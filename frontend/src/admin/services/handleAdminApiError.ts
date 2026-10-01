import { useAdminAuthStore } from '@/admin/stores/useAdminAuthStore'

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
  return Promise.reject({ message: data?.message ?? error.message, ...data })
}
