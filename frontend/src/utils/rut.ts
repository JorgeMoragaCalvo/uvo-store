// GAP-18. El backend guarda el RUT sin puntos y con guion (12345678-5, ver ChileanRut); se muestra con puntos.
// Un valor que no tenga esa forma se devuelve tal cual: mejor mostrarlo raro que no mostrarlo.
export function formatRut(rut: string): string {
  const match = /^(\d{7,8})-([\dK])$/.exec(rut)
  if (!match) return rut
  const [, body, checkDigit] = match
  return `${body.replace(/\B(?=(\d{3})+(?!\d))/g, '.')}-${checkDigit}`
}
