// F20: la fecha LOCAL, no la UTC. `toISOString()` devolvía el día siguiente al abrir la pantalla por la
// tarde en Chile (UTC-3), así que el rango por defecto de los informes proponía como "hasta" un día que
// aún no llegaba.
//
// Vive aquí y no junto al componente que la usa porque `react-refresh/only-export-components` no permite
// que un fichero exporte un componente y además otra cosa: el fast refresh de Vite deja de funcionar en
// ese fichero.
export function toDateInputValue(date: Date): string {
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}
