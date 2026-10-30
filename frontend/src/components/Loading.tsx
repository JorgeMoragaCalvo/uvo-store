const SIZES = { sm: 'h-4 w-4 border-2', md: 'h-8 w-8 border-2', lg: 'h-12 w-12 border-[3px]' } as const

// PROD-24b. role="status" y un texto oculto: el spinner solo era un círculo que gira, y un lector de
// pantalla no decía nada mientras la página cargaba.
export default function Loading({ size = 'md', label = 'Cargando…' }: { size?: keyof typeof SIZES; label?: string }) {
  return (
    <div role="status" className="flex items-center justify-center p-4">
      <div aria-hidden="true" className={`animate-spin rounded-full border-gray-400 border-t-primary ${SIZES[size]}`} />
      <span className="sr-only">{label}</span>
    </div>
  )
}
