import { Outlet } from 'react-router-dom'

// Header/Footer are wired in Paso 2 (useStoreSettingsStore) — plain Outlet for now.
export default function RootLayout() {
  return (
    <div className="flex min-h-svh flex-col">
      <main className="flex-1">
        <Outlet />
      </main>
    </div>
  )
}
