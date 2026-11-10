import { describe, expect, it } from 'vitest'
import { useRef } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Link, MemoryRouter, Route, Routes, useSearchParams } from 'react-router-dom'
import { useFocusOnRouteChange } from './useFocusOnRouteChange'

function Layout() {
  const main = useRef<HTMLElement>(null)
  useFocusOnRouteChange(main)
  return (
    <>
      <Link to="/ficha">Ir a la ficha</Link>
      <main ref={main} tabIndex={-1}>
        <Routes>
          <Route path="/" element={<Search />} />
          <Route path="/ficha" element={<h1>Ficha</h1>} />
        </Routes>
      </main>
    </>
  )
}

function Search() {
  const [params, setParams] = useSearchParams()
  return <input aria-label="Buscar" value={params.get('q') ?? ''} onChange={(e) => setParams({ q: e.target.value })} />
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Layout />
    </MemoryRouter>,
  )
}

describe('useFocusOnRouteChange', () => {
  it('leaves focus alone on the first render', () => {
    renderAt('/')
    expect(screen.getByRole('main')).not.toHaveFocus()
  })

  it('moves focus to the main region when the path changes', async () => {
    const user = userEvent.setup()
    renderAt('/')

    await user.click(screen.getByRole('link', { name: 'Ir a la ficha' }))

    expect(screen.getByRole('heading', { name: 'Ficha' })).toBeInTheDocument()
    expect(screen.getByRole('main')).toHaveFocus()
  })

  it('keeps focus in a field that only changes the query string', async () => {
    const user = userEvent.setup()
    renderAt('/')

    await user.type(screen.getByRole('textbox', { name: 'Buscar' }), 'té')

    expect(screen.getByRole('textbox', { name: 'Buscar' })).toHaveFocus()
    expect(screen.getByRole('textbox', { name: 'Buscar' })).toHaveValue('té')
  })
})
