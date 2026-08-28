import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import adminApi from '@/admin/services/adminApi'
import type { PermissionDto } from '@/admin/types/admin'

export default function RoleForm() {
  const { id } = useParams()
  const isEdit = Boolean(id)
  const navigate = useNavigate()

  const [permissionGroups, setPermissionGroups] = useState<Record<string, PermissionDto[]>>({})
  const [name, setName] = useState('')
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set())
  const [loading, setLoading] = useState(isEdit)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    adminApi.roles.permissions().then(setPermissionGroups).catch(() => toast.error('No se pudieron cargar los permisos'))
  }, [])

  useEffect(() => {
    if (!id) return
    adminApi.roles
      .getById(Number(id))
      .then((role) => {
        setName(role.name)
        setSelectedIds(new Set(role.permissions.map((permission) => permission.id)))
      })
      .catch(() => toast.error('No se pudo cargar el rol'))
      .finally(() => setLoading(false))
  }, [id])

  function togglePermission(permissionId: number, checked: boolean) {
    setSelectedIds((current) => {
      const next = new Set(current)
      if (checked) next.add(permissionId)
      else next.delete(permissionId)
      return next
    })
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    try {
      const request = { name, permissionIds: Array.from(selectedIds) }
      if (isEdit) {
        await adminApi.roles.update(Number(id), request)
        toast.success('Rol actualizado')
      } else {
        await adminApi.roles.create(request)
        toast.success('Rol creado')
      }
      navigate('/admin/roles')
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudo guardar el rol')
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return <p className="text-muted-foreground">Cargando…</p>
  }

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{isEdit ? 'Editar rol' : 'Nuevo rol'}</h1>

      <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
        <Card>
          <CardHeader>
            <CardTitle>Información general</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor="name">Nombre</Label>
              <Input id="name" required value={name} onChange={(event) => setName(event.target.value)} />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Permisos</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-6">
            {Object.entries(permissionGroups).map(([group, permissions]) => (
              <div key={group} className="flex flex-col gap-2">
                <p className="text-sm font-medium capitalize">{group}</p>
                <div className="grid gap-2 sm:grid-cols-2">
                  {permissions.map((permission) => (
                    <div key={permission.id} className="flex items-center justify-between rounded-md border px-3 py-2">
                      <Label htmlFor={`permission-${permission.id}`} className="font-normal">
                        {permission.name}
                      </Label>
                      <Switch
                        id={`permission-${permission.id}`}
                        checked={selectedIds.has(permission.id)}
                        onCheckedChange={(checked) => togglePermission(permission.id, checked)}
                      />
                    </div>
                  ))}
                </div>
              </div>
            ))}
            {Object.keys(permissionGroups).length === 0 && (
              <p className="text-sm text-muted-foreground">No hay permisos disponibles.</p>
            )}
          </CardContent>
        </Card>

        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={() => navigate('/admin/roles')}>
            Cancelar
          </Button>
          <Button type="submit" disabled={saving}>
            {saving ? 'Guardando…' : 'Guardar'}
          </Button>
        </div>
      </form>
    </div>
  )
}
