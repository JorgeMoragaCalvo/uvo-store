import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Download, Search } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import DataTablePagination from '@/admin/components/DataTablePagination'
import adminApi, { type ReportDateRangeParams } from '@/admin/services/adminApi'
import { downloadCsv } from '@/admin/lib/downloadCsv'
import type { Page } from '@/types/api'
import type { CategoryRevenueDto, ProductReportRowDto, ProductsSummaryDto } from '@/admin/types/admin'

function formatCurrency(value: number) {
  return new Intl.NumberFormat('es-CL', { style: 'currency', currency: 'CLP' }).format(value)
}

export default function ProductsReportTab({ dateRange }: { dateRange: ReportDateRangeParams }) {
  const [summary, setSummary] = useState<ProductsSummaryDto | null>(null)
  const [rows, setRows] = useState<Page<ProductReportRowDto> | null>(null)
  const [byCategory, setByCategory] = useState<CategoryRevenueDto[]>([])
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(1)
  const [exporting, setExporting] = useState(false)

  useEffect(() => {
    adminApi.reports.products.summary({ ...dateRange, search: search || undefined }).then(setSummary).catch(() => setSummary(null))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dateRange.startDate, dateRange.endDate, search])

  useEffect(() => {
    adminApi.reports.products
      .list({ ...dateRange, search: search || undefined, page })
      .then(setRows)
      .catch(() => toast.error('No se pudo cargar el reporte de productos'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dateRange.startDate, dateRange.endDate, search, page])

  useEffect(() => {
    adminApi.reports.products
      .byCategory(dateRange)
      .then(setByCategory)
      .catch(() => toast.error('No se pudo cargar el desglose por categoría'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dateRange.startDate, dateRange.endDate])

  async function handleExport() {
    setExporting(true)
    try {
      const blob = await adminApi.reports.exportCsv('/reports/products/export', { ...dateRange, search: search || undefined })
      downloadCsv(blob, 'reporte-productos.csv')
    } catch {
      toast.error('No se pudo exportar el reporte')
    } finally {
      setExporting(false)
    }
  }

  const cards = [
    { label: 'Ingresos totales', value: summary ? formatCurrency(summary.totalRevenue) : undefined },
    { label: 'Unidades vendidas', value: summary?.totalQuantity },
    { label: 'Productos con ventas', value: summary?.uniqueProducts },
    { label: 'Precio promedio', value: summary ? formatCurrency(summary.averagePrice) : undefined },
  ]

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="relative w-full max-w-sm">
          <Search className="absolute top-2.5 left-2.5 size-4 text-muted-foreground" />
          <Input
            placeholder="Buscar producto…"
            className="pl-8"
            value={search}
            onChange={(event) => {
              setPage(1)
              setSearch(event.target.value)
            }}
          />
        </div>
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

      <div className="rounded-lg border bg-background">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Producto</TableHead>
              <TableHead>Categoría</TableHead>
              <TableHead>Stock</TableHead>
              <TableHead>Cantidad vendida</TableHead>
              <TableHead>Ingresos</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows?.content.length === 0 && (
              <TableRow>
                <TableCell colSpan={5} className="py-8 text-center text-muted-foreground">
                  Sin datos.
                </TableCell>
              </TableRow>
            )}
            {rows?.content.map((row) => (
              <TableRow key={row.id}>
                <TableCell>
                  <p className="font-medium">{row.name}</p>
                  <p className="text-sm text-muted-foreground">{row.sku}</p>
                </TableCell>
                <TableCell className="text-muted-foreground">{row.categoryName ?? '—'}</TableCell>
                <TableCell>{row.stock}</TableCell>
                <TableCell>{row.totalQuantity}</TableCell>
                <TableCell>{formatCurrency(row.totalRevenue)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      {rows && (
        <DataTablePagination page={page} totalPages={rows.totalPages} totalElements={rows.totalElements} onPageChange={setPage} />
      )}

      <Card>
        <CardHeader>
          <CardTitle>Ingresos por categoría</CardTitle>
        </CardHeader>
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Categoría</TableHead>
                <TableHead>Productos</TableHead>
                <TableHead>Cantidad</TableHead>
                <TableHead>Ingresos</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {byCategory.length === 0 && (
                <TableRow>
                  <TableCell colSpan={4} className="py-6 text-center text-muted-foreground">
                    Sin datos.
                  </TableCell>
                </TableRow>
              )}
              {byCategory.map((category) => (
                <TableRow key={category.id}>
                  <TableCell className="font-medium">{category.name}</TableCell>
                  <TableCell>{category.productsCount}</TableCell>
                  <TableCell>{category.totalQuantity}</TableCell>
                  <TableCell>{formatCurrency(category.totalRevenue)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  )
}
