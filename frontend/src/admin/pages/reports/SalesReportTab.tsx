import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Download } from 'lucide-react'
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import adminApi, { type ReportDateRangeParams } from '@/admin/services/adminApi'
import { downloadCsv } from '@/admin/lib/downloadCsv'
import type { PaymentMethodRevenueDto, SalesByDayDto, SalesSummaryDto, TopProductDto } from '@/admin/types/admin'

function formatCurrency(value: number) {
  return new Intl.NumberFormat('es-CL', { style: 'currency', currency: 'CLP' }).format(value)
}

export default function SalesReportTab({ dateRange }: { dateRange: ReportDateRangeParams }) {
  const [summary, setSummary] = useState<SalesSummaryDto | null>(null)
  const [byDay, setByDay] = useState<SalesByDayDto[]>([])
  const [topProducts, setTopProducts] = useState<TopProductDto[]>([])
  const [byPaymentMethod, setByPaymentMethod] = useState<PaymentMethodRevenueDto[]>([])
  const [exporting, setExporting] = useState(false)

  useEffect(() => {
    adminApi.reports.sales.summary(dateRange).then(setSummary).catch(() => setSummary(null))
    adminApi.reports.sales.byDay(dateRange).then(setByDay).catch(() => toast.error('No se pudo cargar la serie de ventas'))
    adminApi.reports.sales
      .topProducts(dateRange)
      .then(setTopProducts)
      .catch(() => toast.error('No se pudieron cargar los productos más vendidos'))
    adminApi.reports.sales
      .byPaymentMethod(dateRange)
      .then(setByPaymentMethod)
      .catch(() => toast.error('No se pudo cargar el desglose por método de pago'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dateRange.startDate, dateRange.endDate])

  async function handleExport() {
    setExporting(true)
    try {
      const blob = await adminApi.reports.exportCsv('/reports/sales/export', dateRange)
      downloadCsv(blob, 'reporte-ventas.csv')
    } catch {
      toast.error('No se pudo exportar el reporte')
    } finally {
      setExporting(false)
    }
  }

  const cards = [
    { label: 'Órdenes totales', value: summary?.totalOrders },
    { label: 'Ingresos totales', value: summary ? formatCurrency(summary.totalRevenue) : undefined },
    { label: 'Ticket promedio', value: summary ? formatCurrency(summary.averageOrderValue) : undefined },
    { label: 'Órdenes pagadas', value: summary?.paidOrders },
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
          <CardTitle>Ventas por día</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="h-72 w-full">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={byDay}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="date" tick={{ fontSize: 12 }} />
                <YAxis tick={{ fontSize: 12 }} />
                <Tooltip formatter={(value) => formatCurrency(Number(value))} />
                <Area type="monotone" dataKey="revenue" stroke="var(--color-primary, #2563eb)" fill="var(--color-primary, #2563eb)" fillOpacity={0.2} />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </CardContent>
      </Card>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Productos más vendidos</CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Producto</TableHead>
                  <TableHead>Cantidad</TableHead>
                  <TableHead>Ingresos</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {topProducts.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={3} className="py-6 text-center text-muted-foreground">
                      Sin datos.
                    </TableCell>
                  </TableRow>
                )}
                {topProducts.map((product) => (
                  <TableRow key={product.id}>
                    <TableCell className="font-medium">{product.name}</TableCell>
                    <TableCell>{product.totalQuantity}</TableCell>
                    <TableCell>{formatCurrency(product.totalRevenue)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Ingresos por método de pago</CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Método</TableHead>
                  <TableHead>Órdenes</TableHead>
                  <TableHead>Ingresos</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {byPaymentMethod.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={3} className="py-6 text-center text-muted-foreground">
                      Sin datos.
                    </TableCell>
                  </TableRow>
                )}
                {byPaymentMethod.map((row) => (
                  <TableRow key={row.paymentMethod}>
                    <TableCell className="font-medium">{row.paymentMethod}</TableCell>
                    <TableCell>{row.ordersCount}</TableCell>
                    <TableCell>{formatCurrency(row.totalRevenue)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
