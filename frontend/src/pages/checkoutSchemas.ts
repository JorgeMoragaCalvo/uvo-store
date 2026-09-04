import { z } from 'zod'

// A7: the checkout used to "validate" with two truthiness booleans — `customer.email &&
// customer.firstName && ...` — which accepted a single character in every field and any string as
// an email. It's the most important form in the app and it was the worst validated one. These are
// the first zod schemas in the codebase; the packages were installed but never imported.

export const contactSchema = z.object({
  email: z.string().min(1, 'El email es obligatorio').email('Ingresa un email válido'),
  firstName: z.string().trim().min(2, 'Ingresa tu nombre'),
  lastName: z.string().trim().min(2, 'Ingresa tu apellido'),
  // Deliberately loose: Chilean numbers get written +56 9 1234 5678, 912345678, (2) 2345 6789…
  // Rejecting on shape would lose real orders, so this only demands enough digits to be a phone.
  phone: z
    .string()
    .trim()
    .min(8, 'Ingresa un teléfono de contacto')
    .regex(/^[+\d][\d\s()-]*$/, 'El teléfono solo puede contener números, espacios, + ( ) y -'),
})

export const addressSchema = z.object({
  addressLine1: z.string().trim().min(4, 'Ingresa tu dirección'),
  addressLine2: z.string().trim().optional(),
  city: z.string().trim().min(2, 'Ingresa tu ciudad'),
  postalCode: z.string().trim().min(3, 'Ingresa tu código postal'),
  // Chosen from the store's own coverage, never typed: zone matching is an exact string compare
  // against free text an admin entered, so a typed value would almost never match — which is how
  // shipping ended up free on every order.
  region: z.string().min(1, 'Selecciona una región'),
  commune: z.string().optional(),
})

export type ContactValues = z.infer<typeof contactSchema>
export type AddressValues = z.infer<typeof addressSchema>
