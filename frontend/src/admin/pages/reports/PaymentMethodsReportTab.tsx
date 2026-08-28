import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Download } from 'lucide-react'
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import adminApi, { type ReportDateRangeParams } from '@/admin/services/adminApi'
import { downloadCsv } from '@/admin/lib/downloadCsv'
import type { PaymentMethodDetailDto, PaymentMethodsSummaryDto, PaymentStatusDistributionDto } from '@/admin/types/admin'

function formatCurrency(value: number) {
  return new Intl.NumberFormat('es-CL', { style: 'currency', currency: 'CLP' }).format(value)
}

export default function PaymentMethodsReportTab({ dateRange }: { dateRange: ReportDateRangeParams }) {
  const [summary, setSummary] = useState<PaymentMethodsSummaryDto | null>(null)
  const [details, setDetails] = useState<PaymentMethodDetailDto[]>([])
  const [distribution, setDistribution] = useState<PaymentStatusDistributionDto[]>([])
  const [exporting, setExporting] = useState(false)

  useEffect(() => {
    adminApi.reports.paymentMethods.summary(dateRange).then(setSummary).catch(() => setSummary(null))
    adminApi.reports.paymentMethods.list(dateRange).then(setDetails).catch(() => toast.error('No se pudo cargar el detalle por método'))
    adminApi.reports.paymentMethods
      .statusDistribution(dateRange)
      .then(setDistribution)
      .catch(() => toast.error('No se pudo cargar la distribución por estado'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dateRange.startDate, dateRange.endDate])

  async function handleExport() {
    setExporting(true)
    try {
      const blob = await adminApi.reports.exportCsv('/reports/payment-methods/export', dateRange)
      downloadCsv(blob, 'reporte-metodos-de-pago.csv')
    } catch {
      toast.error('No se pudo exportar el reporte')
    } finally {
      setExporting(false)
    }
  }

  const cards = [
    { label: 'Órdenes totales', value: summary?.totalOrders },
    { label: 'Ingresos totales', value: summary ? formatCurrency(summary.totalRevenue) : undefined },
    { label: 'Pagadas', value: summary?.totalPaid },
    { label: 'Pendientes/fallidas', value: summary ? summary.totalPending + summary.totalFailed : undefined },
  ]

  return (
    <div className="flex flex-col gap-4">
      <div className="flex justify-end">
        <Button variant="outline" size="sm" disabled={exporting} onClick={handleExport}>
          <Download className="size-4" />
          Exportar CSV
        </Button>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {cards.map((card) => (
          <Card key={card.label}>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm font-medium text-muted-foreground">{card.label}</CardTitle>
            </CardHeader>
            <CardContent>
              {card.value === undefined ? <Skeleton className="h-8 w-16" /> : <div className="text-2xl font-bold">{card.value}</div>}
            </CardContent>
          </Card>
        ))}
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Distribución por estado de pago</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="h-64 w-full">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={distribution}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="paymentStatus" tick={{ fontSize: 12 }} />
                <YAxis tick={{ fontSize: 12 }} />
                <Tooltip />
                <Bar dataKey="count" fill="var(--color-primary, #2563eb)" />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Detalle por método de pago</CardTitle>
        </CardHeader>
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Método</TableHead>
                <TableHead>Órdenes</TableHead>
                <TableHead>Ingresos</TableHead>
                <TableHead>Ticket promedio</TableHead>
                <TableHead>Tasa de éxito</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {details.length === 0 && (
                <TableRow>
                  <TableCell colSpan={5} className="py-8 text-center text-muted-foreground">
                    Sin datos.
                  </TableCell>
                </TableRow>
              )}
              {details.map((detail) => (
                <TableRow key={detail.paymentMethod}>
                  <TableCell className="font-medium">{detail.paymentMethod}</TableCell>
                  <TableCell>{detail.ordersCount}</TableCell>
                  <TableCell>{formatCurrency(detail.totalRevenue)}</TableCell>
                  <TableCell>{formatCurrency(detail.averageOrderValue)}</TableCell>
                  <TableCell>{(detail.successRate * 100).toFixed(1)}%</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  )
}
