import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import * as Sentry from '@sentry/react'
import './index.css'
import App from './App.tsx'
import ErrorBoundary from './components/ErrorBoundary.tsx'
import { buildSentryOptions } from './lib/sentry'
import { reloadOnceForStaleChunk } from './lib/staleChunk'

// Off unless VITE_SENTRY_DSN is set — same "external integration, inactive until configured"
// pattern used everywhere else in this project (Stripe/Webpay/mail/etc.). Environment, release and the
// scrubbing of `?token=` from URLs (PROD-15a) live in lib/sentry.ts, where they can be tested.
const sentryOptions = buildSentryOptions(import.meta.env)
if (sentryOptions) {
  Sentry.init(sentryOptions)
}

// IMP-04. Vite avisa con este evento cuando no puede cargar un chunk (lazy o su preload): tras un despliegue, una
// pestaña vieja pide archivos que ya no están. Recargar una vez trae el index.html vigente; si ya se intentó, el
// error sigue su curso y lo muestra RouteError (lib/staleChunk.ts).
window.addEventListener('vite:preloadError', (event) => {
  if (reloadOnceForStaleChunk()) event.preventDefault()
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </StrictMode>,
)
