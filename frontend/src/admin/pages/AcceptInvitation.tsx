import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { useAdminAuthStore } from '@/admin/stores/useAdminAuthStore'
import adminApi from '@/admin/services/adminApi'

/**
 * PROD-06. Donde el administrador invitado elige su contraseña.
 *
 * El correo de invitación enlazaba aquí desde que existe el envío, y esta ruta **no existía**: el invitado
 * recibía un 404 y la única forma de terminar el flujo era llamar a la API a mano. Es la mitad que faltaba.
 *
 * Calcado de `ResetPassword.tsx`, que ya resuelve esta misma forma (token en el query string, contraseña
 * más confirmación, estados de carga y error). Las diferencias son las que pide el criterio de PROD-06:
 * los finales malos tienen que decir qué hacer, y **ninguno pierde lo que ya estaba escrito**.
 *
 * Y a diferencia del reset, aquí se entra directo al panel en vez de rebotar al login: el endpoint
 * devuelve la sesión con los permisos del rol —acaba de demostrar que controla el buzón y acaba de fijar
 * él mismo la contraseña—, así que mandarlo a teclearla otra vez no protegería de nada.
 */
export default function AcceptInvitation() {
  const [searchParams] = useSearchParams()
  const token = searchParams.get('token') ?? ''
  const navigate = useNavigate()
  const login = useAdminAuthStore((state) => state.login)

  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // Separado de `error` porque los dos finales malos piden cosas distintas: uno se arregla pidiendo otra
  // invitación, el otro volviendo a pulsar el botón.
  const [rejected, setRejected] = useState(false)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setRejected(false)

    if (password !== confirmPassword) {
      setError('Las contraseñas no coinciden')
      return
    }

    setLoading(true)
    try {
      const response = await adminApi.auth.acceptInvitation(token, password)
      login(response.token, {
        id: response.id,
        name: response.name,
        email: response.email,
        // A1: sin los permisos el panel se dibuja vacío, que es el síntoma que describió F08.
        permissions: response.permissions ?? [],
      })
      navigate('/admin', { replace: true })
    } catch (err) {
      // El interceptor de `adminApi` rechaza con el cuerpo del ApiError esparcido, y ese cuerpo lleva
      // `status`. Si no hay `status` es que no hubo respuesta: la petición no llegó.
      const failure = err as { message?: string; status?: number }
      if (failure.status === undefined) {
        setError('No pudimos conectar con el servidor. Revisa tu conexión y vuelve a intentarlo.')
      } else {
        // El servidor no distingue entre token inventado, caducado, ya usado o de una cuenta desactivada
        // —a quien prueba tokens no se le cuenta en qué se equivocó—, así que el único consejo honesto es
        // pedir una invitación nueva.
        setRejected(true)
        setError(failure.message ?? 'La invitación no es válida o ha expirado')
      }
    } finally {
      // Lo que NO se toca: la contraseña escrita. Volver a teclearla por un SMTP lento o un enlace viejo
      // es exactamente el «perder el estado de forma confusa» que el plan pide evitar.
      setLoading(false)
    }
  }

  if (!token) {
    return (
      <div className="flex min-h-svh items-center justify-center bg-muted/30 p-4">
        <Card className="w-full max-w-sm">
          <CardHeader>
            <CardTitle>Enlace incompleto</CardTitle>
            <CardDescription>
              Falta el token de la invitación en la dirección. Copia el enlace del correo completo, sin
              cortarlo.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <Link to="/admin/login" className="text-sm underline">
              Ir al inicio de sesión
            </Link>
          </CardContent>
        </Card>
      </div>
    )
  }

  return (
    <div className="flex min-h-svh items-center justify-center bg-muted/30 p-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>Elige tu contraseña</CardTitle>
          <CardDescription>
            Te invitaron a administrar esta tienda. Define tu contraseña para entrar al panel: nadie más la
            conoce.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
            <div className="flex flex-col gap-2">
              <Label htmlFor="password">Contraseña</Label>
              <Input
                id="password"
                type="password"
                autoComplete="new-password"
                required
                minLength={8}
                value={password}
                onChange={(event) => setPassword(event.target.value)}
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="confirmPassword">Confirmar contraseña</Label>
              <Input
                id="confirmPassword"
                type="password"
                autoComplete="new-password"
                required
                minLength={8}
                value={confirmPassword}
                onChange={(event) => setConfirmPassword(event.target.value)}
              />
            </div>
            {error && <p className="text-sm text-destructive">{error}</p>}
            {rejected && (
              <p className="text-sm text-muted-foreground">
                Pídele a quien te dio de alta que te reenvíe la invitación desde el panel, en Usuarios.
              </p>
            )}
            <Button type="submit" disabled={loading} className="mt-2">
              {loading ? 'Guardando…' : 'Entrar al panel'}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
