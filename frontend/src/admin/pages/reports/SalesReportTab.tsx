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
  const [summaryFailed, setSummaryFailed] = useState(false)

  useEffect(() => {
    adminApi.reports.sales
      .summary(dateRange)
      .then((result) => {
        setSummary(result)
        setSummaryFailed(false)
      })
      .catch(() => {
        setSummary(null)
        setSummaryFailed(true)
      })
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

  // F20: las tres cifras. "Ingresos netos" es lo que de verdad quedó; el bruto y los reembolsos van
  // aparte, porque el neto por sí solo no dice si un mes flojo fue por vender poco o por devolver mucho.
  const cards = [
    { label: 'Órdenes totales', value: summary?.totalOrders },
    { label: 'Ingresos netos', value: summary ? formatCurrency(summary.totalRevenue) : undefined },
    { label: 'Ingresos brutos', value: summary ? formatCurrency(summary.grossRevenue) : undefined },
    { label: 'Reembolsos', value: summary ? formatCurrency(summary.refundedAmount) : undefined },
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

      {/* PROD-24b. Si el resumen fallaba, las tarjetas quedaban cargando para siempre y nadie lo decía. */}
      {summaryFailed && (
        <p role="alert" className="rounded-md bg-destructive/10 p-3 text-sm text-destructive">
          No se pudo cargar el resumen del período. Vuelve a intentarlo cambiando las fechas o recargando la página.
        </p>
      )}

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {cards.map((card) => (
          <Card key={card.label}>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm font-medium text-muted-foreground">{card.label}</CardTitle>
            </CardHeader>
            <CardContent>
              {card.value !== undefined ? (
                <div className="text-2xl font-bold">{card.value}</div>
              ) : summaryFailed ? (
                <div className="text-2xl font-bold text-muted-foreground">
                  <span aria-hidden="true">—</span>
                  <span className="sr-only">Sin datos</span>
                </div>
              ) : (
                <Skeleton className="h-8 w-16" />
              )}
            </CardContent>
          </Card>
        ))}
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Ventas por día</CardTitle>
        </CardHeader>
        <CardContent>
          {/* PROD-24b. El gráfico es solo una imagen para un lector de pantalla: el resumen va en el
              aria-label y los valores, en una tabla oculta. */}
          <div
            className="h-72 w-full"
            role="img"
            aria-label={
              byDay.length === 0
                ? 'Ventas por día: sin datos en el período'
                : `Ventas por día, ${byDay.length} días: total ${formatCurrency(byDay.reduce((sum, day) => sum + day.revenue, 0))}`
            }
          >
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
          <table className="sr-only">
            <caption>Ventas por día</caption>
            <thead>
              <tr>
                <th scope="col">Fecha</th>
                <th scope="col">Ingresos</th>
                <th scope="col">Órdenes</th>
              </tr>
            </thead>
            <tbody>
              {byDay.map((day) => (
                <tr key={day.date}>
                  <td>{day.date}</td>
                  <td>{formatCurrency(day.revenue)}</td>
                  <td>{day.ordersCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
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
