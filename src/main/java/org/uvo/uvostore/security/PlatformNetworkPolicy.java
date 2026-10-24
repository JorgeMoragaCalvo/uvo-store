package org.uvo.uvostore.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * POS-6. Desde qué redes se puede llamar a {@code /api/platform/**}.
 *
 * <p>Esa API crea tiendas y cambia sus dominios. Hasta ahora la protegía solo {@code X-Platform-Key}, desde
 * cualquier red: quien la tuviera, o quien la probara sin límite, podía crear tiendas desde Internet. Ahora la
 * llama el backend de UvoPOS por la red privada de la plataforma, y el formulario de operador se usa desde esa
 * misma red (VPN o túnel), así que no hay razón para que sea alcanzable desde fuera.
 *
 * <p>La lista es {@code app.platform.allowed-networks} ({@code PLATFORM_ALLOWED_NETWORKS}). Por defecto, loopback
 * y los rangos privados, que es lo que ve una llamada interna en cualquier plataforma de contenedores.
 * <ul>
 *   <li><b>Una entrada mal escrita impide el arranque.</b> Ignorarla, como hace la lista de proxies, achicaría en
 *       silencio la lista y dejaría al POS fuera sin que nadie supiera por qué.</li>
 *   <li><b>Una lista vacía cierra la API</b>: nadie entra. Es lo que pide quien la vacía.</li>
 * </ul>
 *
 * <p>La dirección sale de {@link ClientIpResolver#resolveStrict}, que dice "no sé" ante cualquier reenvío que no
 * pueda verificar. Y se compara con {@link IpRanges}, que distingue IPv4 de IPv6.
 */
@Component
public class PlatformNetworkPolicy {

    private static final Logger log = LoggerFactory.getLogger(PlatformNetworkPolicy.class);

    private final IpRanges allowedNetworks;
    private final ClientIpResolver ipResolver;

    public PlatformNetworkPolicy(@Value("${app.platform.allowed-networks:}") String allowedNetworks,
                                 ClientIpResolver ipResolver) {
        try {
            this.allowedNetworks = IpRanges.parseStrict(allowedNetworks);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("PLATFORM_ALLOWED_NETWORKS: " + e.getMessage()
                    + ". Usa direcciones o CIDR separados por coma, por ejemplo 10.0.0.0/8,fc00::/7.", e);
        }
        this.ipResolver = ipResolver;
        if (this.allowedNetworks.isEmpty()) {
            log.warn("PLATFORM_ALLOWED_NETWORKS está vacío: la API de plataforma (/api/platform/**) no acepta "
                    + "llamadas desde ninguna red.");
        } else {
            log.info("API de plataforma accesible solo desde [{}]", this.allowedNetworks.describe());
        }
    }

    /** Si la petición viene, con certeza, de una red permitida. */
    public boolean allows(HttpServletRequest request) {
        return ipResolver.resolveStrict(request).map(allowedNetworks::matches).orElse(false);
    }
}
