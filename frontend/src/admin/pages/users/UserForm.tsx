import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Switch } from '@/components/ui/switch'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import adminApi from '@/admin/services/adminApi'
import type { RoleDto } from '@/admin/types/admin'

interface FormState {
  name: string
  email: string
  phone: string
  password: string
  roleId: string
  active: boolean
  notes: string
}

const EMPTY_FORM: FormState = {
  name: '',
  email: '',
  phone: '',
  password: '',
  roleId: '',
  active: true,
  notes: '',
}

export default function UserForm() {
  const { id } = useParams()
  const isEdit = Boolean(id)
  const navigate = useNavigate()

  const [roles, setRoles] = useState<RoleDto[]>([])
  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [avatar, setAvatar] = useState<File | null>(null)
  const [currentAvatar, setCurrentAvatar] = useState<string | null>(null)
  const [loading, setLoading] = useState(isEdit)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    adminApi.roles.list().then(setRoles).catch(() => toast.error('No se pudieron cargar los roles'))
  }, [])

  useEffect(() => {
    if (!id) return
    adminApi.users
      .getById(Number(id))
      .then((user) => {
        setForm({
          name: user.name,
          email: user.email,
          phone: user.phone ?? '',
          password: '',
          roleId: user.roles[0] ? String(user.roles[0].id) : '',
          active: user.active,
          notes: user.notes ?? '',
        })
        setCurrentAvatar(user.avatar)
      })
      .catch(() => toast.error('No se pudo cargar el usuario'))
      .finally(() => setLoading(false))
  }, [id])

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    try {
      const data = new FormData()
      data.append('name', form.name)
      data.append('email', form.email)
      data.append('phone', form.phone)
      if (form.password) data.append('password', form.password)
      if (form.roleId) data.append('roleId', form.roleId)
      data.append('active', String(form.active))
      data.append('notes', form.notes)
      data.append('sendInvitation', 'false')
      if (avatar) data.append('avatar', avatar)

      if (isEdit) {
        await adminApi.users.update(Number(id), data)
        toast.success('Usuario actualizado')
      } else {
        await adminApi.users.create(data)
        toast.success('Usuario creado')
      }
      navigate('/admin/users')
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudo guardar el usuario')
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return <p className="text-muted-foreground">Cargando…</p>
  }

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{isEdit ? 'Editar usuario' : 'Nuevo usuario'}</h1>

      <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
        <Card>
          <CardHeader>
            <CardTitle>Información general</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="flex flex-col gap-2">
                <Label htmlFor="name">Nombre</Label>
                <Input id="name" required value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} />
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="email">Correo electrónico</Label>
                <Input
                  id="email"
                  type="email"
                  required
                  value={form.email}
                  onChange={(event) => setForm({ ...form, email: event.target.value })}
                />
              </div>
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="flex flex-col gap-2">
                <Label htmlFor="phone">Teléfono</Label>
                <Input id="phone" value={form.phone} onChange={(event) => setForm({ ...form, phone: event.target.value })} />
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="password">{isEdit ? 'Nueva contraseña (opcional)' : 'Contraseña'}</Label>
                <Input
                  id="password"
                  type="password"
                  required={!isEdit}
                  value={form.password}
                  onChange={(event) => setForm({ ...form, password: event.target.value })}
                />
              </div>
            </div>
            <div className="flex flex-col gap-2">
              <Label>Rol</Label>
              <Select value={form.roleId || undefined} onValueChange={(value) => setForm({ ...form, roleId: value })}>
                <SelectTrigger>
                  <SelectValue placeholder="Sin rol asignado" />
                </SelectTrigger>
                <SelectContent>
                  {roles.map((role) => (
                    <SelectItem key={role.id} value={String(role.id)}>
                      {role.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="notes">Notas</Label>
              <Textarea id="notes" rows={3} value={form.notes} onChange={(event) => setForm({ ...form, notes: event.target.value })} />
            </div>
            <div className="flex items-center justify-between rounded-md border px-3 py-2">
              <Label htmlFor="active">Activo</Label>
              <Switch id="active" checked={form.active} onCheckedChange={(checked) => setForm({ ...form, active: checked })} />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Avatar</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            {currentAvatar && <img src={currentAvatar} alt="" className="size-20 rounded-full object-cover" />}
            <div className="flex flex-col gap-2">
              <Label htmlFor="avatar">{currentAvatar ? 'Reemplazar avatar' : 'Avatar'}</Label>
              <Input id="avatar" type="file" accept="image/*" onChange={(event) => setAvatar(event.target.files?.[0] ?? null)} />
            </div>
          </CardContent>
        </Card>

        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={() => navigate('/admin/users')}>
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
