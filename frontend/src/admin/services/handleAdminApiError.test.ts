import { beforeEach, describe, expect, it } from 'vitest'
import { handleAdminApiError } from '@/admin/services/handleAdminApiError'
import { useAdminAuthStore } from '@/admin/stores/useAdminAuthStore'

/**
 * F19. El interceptor del panel solo cerraba sesión ante 401, y el backend respondía 403 cuando el token
 * caducaba o se revocaba, así que la SPA se quedaba con un token muerto: el guarda de ruta no se
 * disparaba —seguía habiendo token— y se veían errores en todas las pantallas sin volver al login.
 *
 * La otra mitad es igual de importante: ante un 403 NO se cierra sesión. Un administrador restringido
 * recibe 403 legítimos de `@PreAuthorize`, y echarlo al login lo dejaría en un bucle.
 */
describe('handleAdminApiError', () => {
  beforeEach(() => {
    useAdminAuthStore.setState({
      token: 'jwt',
      user: { id: 1, name: 'Admin', email: 'admin@test.local', permissions: [] },
      sessionExpired: false,
    })
  })

  it('cierra sesión ante 401 y deja marcada la sesión como vencida', async () => {
    await expect(
      handleAdminApiError({ response: { status: 401, data: { message: 'No autenticado.' } } }),
    ).rejects.toMatchObject({ message: 'No autenticado.' })

    expect(useAdminAuthStore.getState().token).toBeNull()
    expect(useAdminAuthStore.getState().user).toBeNull()
    // Sin esto el administrador aparece en el login sin saber por qué.
    expect(useAdminAuthStore.getState().sessionExpired).toBe(true)
  })

  it('ante 403 mantiene la sesión y propaga el mensaje', async () => {
    await expect(
      handleAdminApiError({ response: { status: 403, data: { message: 'No tienes permiso para esta acción.' } } }),
    ).rejects.toMatchObject({ message: 'No tienes permiso para esta acción.' })

    // El caso del administrador restringido: la sección no le toca, pero su sesión es válida.
    expect(useAdminAuthStore.getState().token).toBe('jwt')
    expect(useAdminAuthStore.getState().sessionExpired).toBe(false)
  })

  it('ante 500 tampoco toca la sesión', async () => {
    await expect(
      handleAdminApiError({ response: { status: 500, data: { message: 'Ocurrió un error inesperado' } } }),
    ).rejects.toMatchObject({ message: 'Ocurrió un error inesperado' })

    expect(useAdminAuthStore.getState().token).toBe('jwt')
  })

  it('sin respuesta (fallo de red) propaga el mensaje de axios y conserva la sesión', async () => {
    await expect(handleAdminApiError({ message: 'Network Error' })).rejects.toMatchObject({
      message: 'Network Error',
    })

    // Un corte de red no es una sesión vencida: cerrar sesión aquí haría perder el trabajo por un
    // problema de conectividad.
    expect(useAdminAuthStore.getState().token).toBe('jwt')
    expect(useAdminAuthStore.getState().sessionExpired).toBe(false)
  })
})
