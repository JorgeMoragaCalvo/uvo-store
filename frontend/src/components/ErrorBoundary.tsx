import { Component } from 'react'
import type { ErrorInfo, ReactNode } from 'react'
import * as Sentry from '@sentry/react'

interface Props {
  children: ReactNode
}

interface State {
  hasError: boolean
}

// Catches otherwise-fatal render errors (a blank white screen) and reports them to Sentry — a
// no-op if VITE_SENTRY_DSN isn't set, see main.tsx.
export default class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false }

  static getDerivedStateFromError(): State {
    return { hasError: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    Sentry.captureException(error, { extra: { componentStack: info.componentStack } })
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="flex min-h-svh flex-col items-center justify-center gap-4 p-8 text-center">
          <h1 className="text-xl font-semibold text-dark">Algo salió mal</h1>
          <p className="text-sm text-secondary">Ocurrió un error inesperado. Intenta recargar la página.</p>
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="rounded bg-primary px-4 py-2 text-sm font-medium text-white"
          >
            Recargar
          </button>
        </div>
      )
    }

    return this.props.children
  }
}
