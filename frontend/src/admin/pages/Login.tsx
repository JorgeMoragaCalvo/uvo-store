import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { useAdminAuthStore } from '@/admin/stores/useAdminAuthStore'
import adminApi from '@/admin/services/adminApi'

export default function Login() {
  const token = useAdminAuthStore((state) => state.token)
  const login = useAdminAuthStore((state) => state.login)
  const navigate = useNavigate()
  const location = useLocation()

  const sessionExpired = useAdminAuthStore((state) => state.sessionExpired)
  const clearSessionExpired = useAdminAuthStore((state) => state.clearSessionExpired)

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  // F19: por qué apareció aquí. El 401 cierra la sesión y RequireAdminAuth redirige, pero sin esto el
  // administrador llega a esta pantalla a mitad de una tarea sin ninguna señal de qué pasó. Se limpia al
  // mostrarlo para que no reaparezca en el siguiente login.
  useEffect(() => {
    if (sessionExpired) {
      toast.info('Tu sesión expiró. Vuelve a iniciar sesión.')
      clearSessionExpired()
    }
  }, [sessionExpired, clearSessionExpired])

  if (token) {
    const redirectTo = (location.state as { from?: Location })?.from?.pathname ?? '/admin'
    return <Navigate to={redirectTo} replace />
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setLoading(true)
    try {
      const response = await adminApi.auth.login(email, password)
      login(response.token, {
        id: response.id,
        name: response.name,
        email: response.email,
        permissions: response.permissions ?? [],
      })
      navigate('/admin', { replace: true })
    } catch (err) {
      const message = (err as { message?: string })?.message
      setError(message ?? 'No se pudo iniciar sesión')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-svh items-center justify-center bg-muted/30 p-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>Panel de administración</CardTitle>
          <CardDescription>Ingresa tus credenciales para continuar</CardDescription>
        </CardHeader>
        <CardContent>
          <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
            <div className="flex flex-col gap-2">
              <Label htmlFor="email">Correo electrónico</Label>
              <Input
                id="email"
                type="email"
                autoComplete="username"
                required
                value={email}
                onChange={(event) => setEmail(event.target.value)}
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="password">Contraseña</Label>
              <Input
                id="password"
                type="password"
                autoComplete="current-password"
                required
                value={password}
                onChange={(event) => setPassword(event.target.value)}
              />
            </div>
            {error && <p className="text-sm text-destructive">{error}</p>}
            <Button type="submit" disabled={loading} className="mt-2">
              {loading ? 'Ingresando…' : 'Ingresar'}
            </Button>
          </form>
          <p className="mt-4 text-center text-sm text-muted-foreground">
            <Link to="/admin/forgot-password" className="underline">
              ¿Olvidaste tu contraseña?
            </Link>
          </p>
        </CardContent>
      </Card>
    </div>
  )
}
