// PROD-14a. Los correos que capturó el doble de SMTP. Todos salen después del commit (la invitación por un
// oyente AFTER_COMMIT, los de la orden por el outbox), que es justo lo que los tests del backend no ejecutan.

import { expect } from '@playwright/test'
import { http } from './http.ts'
import { DOUBLES_URL } from './env.ts'
import type { CapturedMail } from '../doubles/server.ts'

export async function mailsTo(address: string): Promise<CapturedMail[]> {
  const result = await http('GET', `${DOUBLES_URL}/__doubles/mail?to=${encodeURIComponent(address.toLowerCase())}`)
  return result.json as CapturedMail[]
}

/** Espera a que llegue a `address` un correo cuyo asunto contenga `subject`, y lo devuelve. */
export async function waitForMail(address: string, subject: string, timeout = 20_000): Promise<CapturedMail> {
  let found: CapturedMail | undefined
  await expect
    .poll(
      async () => {
        found = (await mailsTo(address)).find((mail) => mail.subject.includes(subject))
        return found !== undefined
      },
      { message: `correo «${subject}» para ${address}`, timeout },
    )
    .toBe(true)
  return found as CapturedMail
}

/** El primer enlace del cuerpo que contenga `path`. */
export function linkIn(mail: CapturedMail, path: string): string {
  const link = mail.body.match(/https?:\/\/\S+/g)?.find((url) => url.includes(path))
  if (!link) throw new Error(`El correo «${mail.subject}» no trae un enlace a ${path}:\n${mail.body}`)
  return link
}
