import type { ReactNode } from 'react'
import { useStoreSettingsStore } from '../../stores/useStoreSettingsStore'

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

  return (
    <div className="bg-gray-50 py-10">
      <div className="mx-auto max-w-3xl px-4">
        <div className="mb-6 rounded-xl border border-gray-200 bg-white p-6">
          <h1 className="text-2xl font-bold text-dark">{title}</h1>
          <p className="mt-1 text-sm text-secondary">{storeName}</p>
          <p className="mt-3 text-sm text-secondary">{intro}</p>
        </div>

        <div className="space-y-6 rounded-xl border border-gray-200 bg-white p-6">
          {sections.map((section) => (
            <section key={section.title}>
              <h2 className="mb-2 border-b border-gray-200 pb-1.5 text-lg font-semibold text-dark">{section.title}</h2>
              <div className="space-y-2 text-sm leading-relaxed text-secondary">{section.body}</div>
            </section>
          ))}

          {settings?.contactEmail && (
            <section>
              <h2 className="mb-2 border-b border-gray-200 pb-1.5 text-lg font-semibold text-dark">Contacto</h2>
              <p className="text-sm text-secondary">
                Email: <a href={`mailto:${settings.contactEmail}`} className="text-primary underline">{settings.contactEmail}</a>
              </p>
            </section>
          )}
        </div>
      </div>
    </div>
  )
}
