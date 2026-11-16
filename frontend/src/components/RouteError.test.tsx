import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import RouteError from './RouteError'

const staleChunk = vi.hoisted(() => ({
  canReloadForStaleChunk: vi.fn<() => boolean>(),
  reloadForStaleChunk: vi.fn<() => boolean>(),
}))

vi.mock('../lib/staleChunk', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../lib/staleChunk')>()),
  ...staleChunk,
}))

function renderWithError(error: unknown) {
  const router = createMemoryRouter(
    [
      {
        path: '/',
        loader: () => {
          throw error
        },
        element: <p>ok</p>,
        errorElement: <RouteError />,
      },
    ],
    { initialEntries: ['/'] },
  )
  return render(<RouterProvider router={router} />)
}

const staleError = new TypeError('Failed to fetch dynamically imported module: http://tienda/assets/Checkout-abc.js')

describe('RouteError', () => {
  beforeEach(() => {
    staleChunk.canReloadForStaleChunk.mockReset()
    staleChunk.reloadForStaleChunk.mockReset()
  })

  it('ante un chunk que no está, recarga sola la primera vez', async () => {
    staleChunk.canReloadForStaleChunk.mockReturnValue(true)
    renderWithError(staleError)
    expect(await screen.findByRole('status')).toHaveTextContent('Actualizando la página')
    // La recarga va en un efecto, que corre después del render: el loader del router resuelve fuera de act(), así
    // que puede no haber corrido todavía cuando aparece el texto.
    await waitFor(() => expect(staleChunk.reloadForStaleChunk).toHaveBeenCalled())
  })

  it('si ya recargó hace poco, muestra «Recargar» en vez de entrar en bucle', async () => {
    staleChunk.canReloadForStaleChunk.mockReturnValue(false)
    renderWithError(staleError)
    expect(await screen.findByRole('button', { name: 'Recargar' })).toBeInTheDocument()
    expect(staleChunk.reloadForStaleChunk).not.toHaveBeenCalled()
  })

  it('otro error no recarga solo', async () => {
    staleChunk.canReloadForStaleChunk.mockReturnValue(true)
    renderWithError(new Error('falló el render'))
    expect(await screen.findByRole('button', { name: 'Recargar' })).toBeInTheDocument()
    expect(staleChunk.reloadForStaleChunk).not.toHaveBeenCalled()
  })
})
