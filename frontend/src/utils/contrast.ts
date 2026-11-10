// GAP-19. Contraste WCAG 2.x entre dos colores, para avisar en el panel cuando un color de marca deja texto que no
// se lee. El storefront pone texto blanco sobre el primario, el primario como texto de los enlaces sobre blanco,
// el secundario y el oscuro como texto sobre blanco, y texto oscuro sobre el acento.

/** El mínimo de WCAG AA para texto normal. */
export const MIN_CONTRAST = 4.5

function channel(value: number): number {
  const srgb = value / 255
  return srgb <= 0.04045 ? srgb / 12.92 : ((srgb + 0.055) / 1.055) ** 2.4
}

/** Luminancia relativa de un color `#rgb` o `#rrggbb`; `null` si no es un hexadecimal válido. */
export function relativeLuminance(hex: string): number | null {
  const match = /^#?([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(hex.trim())
  if (!match) return null
  const digits = match[1].length === 3 ? [...match[1]].map((d) => d + d).join('') : match[1]
  const [r, g, b] = [0, 2, 4].map((i) => channel(parseInt(digits.slice(i, i + 2), 16)))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** Razón de contraste entre dos colores (de 1 a 21); `null` si alguno no es un hexadecimal válido. */
export function contrastRatio(a: string, b: string): number | null {
  const la = relativeLuminance(a)
  const lb = relativeLuminance(b)
  if (la === null || lb === null) return null
  const [light, dark] = la > lb ? [la, lb] : [lb, la]
  return (light + 0.05) / (dark + 0.05)
}

/** La razón como se muestra al comerciante: truncada a dos decimales, para no redondear un 4,496 a «4,50». */
export function formatRatio(ratio: number): string {
  return `${(Math.floor(ratio * 100) / 100).toLocaleString('es-CL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}:1`
}
