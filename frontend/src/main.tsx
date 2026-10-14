import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import * as Sentry from '@sentry/react'
import './index.css'
import App from './App.tsx'
import ErrorBoundary from './components/ErrorBoundary.tsx'
import { buildSentryOptions } from './lib/sentry'

// Off unless VITE_SENTRY_DSN is set — same "external integration, inactive until configured"
// pattern used everywhere else in this project (Stripe/Webpay/mail/etc.). Environment, release and the
// scrubbing of `?token=` from URLs (PROD-15a) live in lib/sentry.ts, where they can be tested.
const sentryOptions = buildSentryOptions(import.meta.env)
if (sentryOptions) {
  Sentry.init(sentryOptions)
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </StrictMode>,
)
