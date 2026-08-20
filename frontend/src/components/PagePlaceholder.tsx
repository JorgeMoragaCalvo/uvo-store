// Temporary stand-in for pages not yet built (see plan Pasos 2-7). Removed page by page as each is implemented.
export default function PagePlaceholder({ name }: { name: string }) {
  return (
    <div className="p-8 text-center text-secondary">
      <p className="text-sm uppercase tracking-wide">Página en construcción</p>
      <h1 className="text-2xl font-semibold text-dark">{name}</h1>
    </div>
  )
}
