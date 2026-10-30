import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/button'

// PROD-24b. Una dirección del panel que no existe dejaba el área de contenido en blanco.
export default function AdminNotFound() {
  return (
    <div className="rounded-lg border border-dashed p-8 text-center">
      <h1 className="text-xl font-semibold">Página no encontrada</h1>
      <p className="mt-1 mb-4 text-sm text-muted-foreground">Esta sección del panel no existe.</p>
      <Button asChild variant="outline">
        <Link to="/admin">Ir al dashboard</Link>
      </Button>
    </div>
  )
}
