import { useState } from 'react'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import SalesReportTab from './SalesReportTab'
import ProductsReportTab from './ProductsReportTab'
import PaymentMethodsReportTab from './PaymentMethodsReportTab'

export default function Reports() {
  const [startDate, setStartDate] = useState('')
  const [endDate, setEndDate] = useState('')

  const dateRange = { startDate: startDate || undefined, endDate: endDate || undefined }

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">Reportes</h1>

      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-2">
          <Label htmlFor="startDate">Desde</Label>
          <Input id="startDate" type="date" value={startDate} onChange={(event) => setStartDate(event.target.value)} />
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor="endDate">Hasta</Label>
          <Input id="endDate" type="date" value={endDate} onChange={(event) => setEndDate(event.target.value)} />
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
