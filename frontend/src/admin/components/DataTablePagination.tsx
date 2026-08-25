import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Button } from '@/components/ui/button'

interface DataTablePaginationProps {
  page: number // 1-based
  totalPages: number
  totalElements: number
  onPageChange: (page: number) => void
}

export default function DataTablePagination({ page, totalPages, totalElements, onPageChange }: DataTablePaginationProps) {
  if (totalElements === 0) return null

  return (
    <div className="flex items-center justify-between px-1 py-3">
      <p className="text-sm text-muted-foreground">
        Página {page} de {Math.max(totalPages, 1)} · {totalElements} resultados
      </p>
      <div className="flex items-center gap-2">
        <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => onPageChange(page - 1)}>
          <ChevronLeft className="size-4" />
          Anterior
        </Button>
        <Button variant="outline" size="sm" disabled={page >= totalPages} onClick={() => onPageChange(page + 1)}>
          Siguiente
          <ChevronRight className="size-4" />
        </Button>
      </div>
    </div>
  )
}
