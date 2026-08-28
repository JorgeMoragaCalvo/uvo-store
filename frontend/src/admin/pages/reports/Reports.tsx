import { useState } from 'react'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import SalesReportTab from './SalesReportTab'
import ProductsReportTab from './ProductsReportTab'
import PaymentMethodsReportTab from './PaymentMethodsReportTab'

function toDateInputValue(date: Date): string {
  return date.toISOString().slice(0, 10)
}

// The backend's report endpoints require startDate/endDate (no default range server-side) — an
// empty range here would omit the params entirely and the request would be rejected, so default
// to the last 30 days instead of leaving the inputs blank.
function defaultRange() {
  const end = new Date()
  const start = new Date()
  start.setDate(start.getDate() - 30)
  return { start: toDateInputValue(start), end: toDateInputValue(end) }
}

export default function Reports() {
  const initialRange = defaultRange()
  const [startDate, setStartDate] = useState(initialRange.start)
  const [endDate, setEndDate] = useState(initialRange.end)

  const dateRange = { startDate, endDate }

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">Reportes</h1>

      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-2">
          <Label htmlFor="startDate">Desde</Label>
          <Input
            id="startDate"
            type="date"
            required
            value={startDate}
            onChange={(event) => event.target.value && setStartDate(event.target.value)}
          />
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor="endDate">Hasta</Label>
          <Input
            id="endDate"
            type="date"
            required
            value={endDate}
            onChange={(event) => event.target.value && setEndDate(event.target.value)}
          />
        </div>
      </div>

      <Tabs defaultValue="sales">
        <TabsList>
          <TabsTrigger value="sales">Ventas</TabsTrigger>
          <TabsTrigger value="products">Productos</TabsTrigger>
          <TabsTrigger value="payment-methods">Métodos de pago</TabsTrigger>
        </TabsList>
        <TabsContent value="sales">
          <SalesReportTab dateRange={dateRange} />
        </TabsContent>
        <TabsContent value="products">
          <ProductsReportTab dateRange={dateRange} />
        </TabsContent>
        <TabsContent value="payment-methods">
          <PaymentMethodsReportTab dateRange={dateRange} />
        </TabsContent>
      </Tabs>
    </div>
  )
}
