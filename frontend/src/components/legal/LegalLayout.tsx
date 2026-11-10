import type { ReactNode } from 'react'
import { useStoreSettingsStore } from '../../stores/useStoreSettingsStore'
import { useStoreTitle } from '../../hooks/useDocumentTitle'
import { formatRut } from '../../utils/rut'

export interface LegalSection {
  title: string
  body: ReactNode
}

export default function LegalLayout({
  title,
  intro,
  sections,
}: {
  title: string
  intro: string
  sections: LegalSection[]
}) {
  const settings = useStoreSettingsStore((state) => state.settings)
  const storeName = settings?.storeName ?? 'UvoStore'
  useStoreTitle(title)

  // GAP-18. Quién vende, con los datos de la configuración de la tienda: cada página legal identifica al
  // proveedor. Cada línea aparece solo si la tienda la completó; la razón social cae al nombre de la tienda.
  const responsible = settings && {
    name: settings.legalName ?? storeName,
    taxId: settings.legalTaxId ? formatRut(settings.legalTaxId) : null,
    address: settings.legalAddress,
    email: settings.contactEmail,
    phone: settings.contactPhone,
  }

  return (
    <div className="bg-gray-50 py-10">
      <div className="mx-auto max-w-3xl px-4">
        <div className="mb-6 rounded-xl border border-gray-400 bg-white p-6">
          <h1 className="text-2xl font-bold text-dark">{title}</h1>
          <p className="mt-1 text-sm text-secondary">{storeName}</p>
          <p className="mt-3 text-sm text-secondary">{intro}</p>
        </div>

        <div className="space-y-6 rounded-xl border border-gray-400 bg-white p-6">
          {sections.map((section) => (
            <section key={section.title}>
              <h2 className="mb-2 border-b border-gray-400 pb-1.5 text-lg font-semibold text-dark">{section.title}</h2>
              <div className="space-y-2 text-sm leading-relaxed text-secondary">{section.body}</div>
            </section>
          ))}

          {responsible && (
            <section>
              <h2 className="mb-2 border-b border-gray-400 pb-1.5 text-lg font-semibold text-dark">Responsable y contacto</h2>
              <ul className="space-y-1 text-sm text-secondary">
                <li>{responsible.name}</li>
                {responsible.taxId && <li>RUT: {responsible.taxId}</li>}
                {responsible.address && <li>Domicilio: {responsible.address}</li>}
                {responsible.email && (
                  <li>
                    Email: <a href={`mailto:${responsible.email}`} className="text-primary underline">{responsible.email}</a>
                  </li>
                )}
                {responsible.phone && (
                  <li>
                    Teléfono:{' '}
                    <a href={`tel:${responsible.phone.replace(/[^\d+]/g, '')}`} className="text-primary underline">
                      {responsible.phone}
                    </a>
                  </li>
                )}
              </ul>
            </section>
          )}
        </div>
      </div>
    </div>
  )
}
