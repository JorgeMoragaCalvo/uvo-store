package org.uvo.uvostore.security;

import org.springframework.security.access.AccessDeniedException;
import org.uvo.uvostore.entity.pos.PosConnection;

/**
 * F01. El único sitio donde se decide a qué empresa —y por tanto a qué tienda— pertenece una petición
 * del POS: <b>la conexión que autenticó el filtro, nunca el cuerpo</b>.
 *
 * <p>Lo que había antes: {@code PosWebhookAuthFilter} y {@code PosApiKeyAuthFilter} resolvían la
 * conexión desde la cabecera {@code X-Company-ID} (y validaban HMAC o API key contra el secreto de
 * <i>esa</i> conexión), la dejaban en el atributo {@code posConnection}… y ningún controlador lo leía.
 * Los controladores tomaban el {@code companyId} del JSON y los servicios escribían donde dijera ese
 * valor. Como cada comerciante se configura sus propias credenciales POS desde Configuración &gt;
 * General ({@code SettingsServiceImpl}, el companyId sale del propio token {@code uvp_<id>_…}), la
 * tienda A podía firmar con su secreto, mandar su cabecera y poner el companyId de B en el cuerpo: el
 * filtro validaba a A y el servicio escribía en B. Con {@code /api/sync/product} incluso creaba
 * productos en el catálogo de B, que no necesita mapping previo.
 *
 * <p><b>Se rechaza, no se ignora en silencio.</b> Devolver el id autenticado y descartar el del cuerpo
 * cerraría el agujero igual, pero si UvoPOS empezara a mandar otro id queremos que se vea: un 403 sale
 * en los logs y en el panel del integrador; un valor descartado en silencio no aparece en ninguna
 * parte y se descubre cuando algo lleva semanas sin sincronizarse.
 */
public final class PosRequestGuard {

    private PosRequestGuard() {
    }

    /**
     * Devuelve el {@code companyId} de la conexión autenticada, comprobando de paso que el cuerpo no
     * declare otro distinto.
     *
     * @param connection        la que dejó el filtro en el atributo {@code posConnection}
     * @param declaredCompanyId el del cuerpo JSON; los DTO lo traen {@code @NotNull}, pero se acepta
     *                          nulo por si algún día deja de venir — en ese caso manda la conexión
     * @throws AccessDeniedException (403) si el cuerpo declara una empresa distinta de la autenticada
     */
    public static Long companyIdOf(PosConnection connection, Long declaredCompanyId) {
        Long authenticated = connection.getCompanyId();
        if (declaredCompanyId != null && !declaredCompanyId.equals(authenticated)) {
            throw new AccessDeniedException(
                    "El companyId del cuerpo no corresponde a la conexión POS autenticada");
        }
        return authenticated;
    }
}
