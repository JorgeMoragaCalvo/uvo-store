import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { Textarea } from '@/components/ui/textarea'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import adminApi from '@/admin/services/adminApi'
import type { BankTransferUpdate } from '@/admin/types/admin'
import type { BankAccountType, BankTransfer } from '@/types/api'

const ACCOUNT_TYPES: Array<{ value: BankAccountType; label: string }> = [
  { value: 'CORRIENTE', label: 'Cuenta corriente' },
  { value: 'VISTA', label: 'Cuenta vista' },
  { value: 'AHORRO', label: 'Cuenta de ahorro' },
  { value: 'RUT', label: 'CuentaRUT' },
]

function toForm(dto: BankTransfer): BankTransferUpdate {
  return {
    enabled: dto.enabled,
    holderName: dto.holderName ?? '',
    holderRut: dto.holderRut ?? '',
    bankName: dto.bankName ?? '',
    accountType: dto.accountType,
    accountNumber: dto.accountNumber ?? '',
    noticeEmail: dto.noticeEmail ?? '',
    instructions: dto.instructions ?? '',
    paymentWindowHours: dto.paymentWindowHours,
  }
}

/**
 * IMP-07. El pago manual de la tienda: una transferencia bancaria. El comprador ve estos datos en el checkout, en la
 * confirmación y en el correo, con el plazo para pagar; pasado el plazo la orden se cancela sola.
 */
export default function BankTransferCard() {
  const [form, setForm] = useState<BankTransferUpdate | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    adminApi.bankTransfer
      .get()
      .then((dto) => setForm(toForm(dto)))
      .catch(() => toast.error('No se pudo cargar la transferencia bancaria'))
  }, [])

  if (!form) return null

  function set<K extends keyof BankTransferUpdate>(key: K, value: BankTransferUpdate[K]) {
    setForm((prev) => (prev ? { ...prev, [key]: value } : prev))
  }

  async function handleSave() {
    if (!form) return
    setSaving(true)
    try {
      const saved = await adminApi.bankTransfer.update(form)
      setForm(toForm(saved))
      toast.success('Transferencia bancaria actualizada')
    } catch (err) {
      const message = (err as { message?: string })?.message
      toast.error(message ?? 'No se pudo guardar la transferencia bancaria')
    } finally {
      setSaving(false)
    }
  }

  const text = (id: keyof BankTransferUpdate, label: string, type = 'text') => (
    <div className="flex flex-col gap-2">
      <Label htmlFor={`bank-${id}`}>{label}</Label>
      <Input
        id={`bank-${id}`}
        type={type}
        value={String(form[id] ?? '')}
        onChange={(event) => set(id, event.target.value as never)}
      />
    </div>
  )

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between gap-4">
          <div>
            <CardTitle>Transferencia bancaria</CardTitle>
            <CardDescription>
              El pago manual: el comprador transfiere a tu cuenta y tú marcas la orden como pagada cuando ves el
              dinero en el banco.
            </CardDescription>
          </div>
          <div className="flex items-center gap-2">
            <Label htmlFor="bank-enabled" className="text-sm text-muted-foreground">
              Habilitado
            </Label>
            <Switch id="bank-enabled" checked={form.enabled} onCheckedChange={(v) => set('enabled', v)} />
          </div>
        </div>
      </CardHeader>
      <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {text('holderName', 'Titular de la cuenta')}
        {text('holderRut', 'RUT del titular')}
        {text('bankName', 'Banco')}
        <div className="flex flex-col gap-2">
          <Label htmlFor="bank-accountType">Tipo de cuenta</Label>
          <select
            id="bank-accountType"
            value={form.accountType ?? ''}
            onChange={(event) => set('accountType', (event.target.value || null) as BankAccountType | null)}
            className="h-8 rounded-lg border border-input bg-transparent px-2.5 text-sm"
          >
            <option value="">Elige uno</option>
            {ACCOUNT_TYPES.map((type) => (
              <option key={type.value} value={type.value}>
                {type.label}
              </option>
            ))}
          </select>
        </div>
        {text('accountNumber', 'Número de cuenta')}
        {text('noticeEmail', 'Correo para el comprobante', 'email')}
        <div className="flex flex-col gap-2">
          <Label htmlFor="bank-paymentWindowHours">Plazo para pagar (horas, de 1 a 168)</Label>
          <Input
            id="bank-paymentWindowHours"
            type="number"
            min={1}
            max={168}
            value={form.paymentWindowHours}
            onChange={(event) => set('paymentWindowHours', Number(event.target.value))}
          />
        </div>
        <div className="flex flex-col gap-2 sm:col-span-2">
          <Label htmlFor="bank-instructions">Instrucciones adicionales (opcional)</Label>
          <Textarea
            id="bank-instructions"
            rows={2}
            value={form.instructions}
            onChange={(event) => set('instructions', event.target.value)}
          />
        </div>
        <div className="flex justify-end sm:col-span-2">
          <Button onClick={handleSave} disabled={saving}>
            {saving ? 'Guardando…' : 'Guardar'}
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}
