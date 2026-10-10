package org.uvo.uvostore.service.url;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.tenant.Store;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * PROD-04. El único sitio que decide cuál es la dirección pública de una tienda.
 *
 * <p>Es la <b>inversa</b> de {@link org.uvo.uvostore.security.StoreHostResolver}: ese va de un nombre de
 * host a una {@code Store} (para resolver el tenant de una petición entrante); este va de una
 * {@code Store} a su origen público (para construir lo que sale — correos, retornos de pasarela y
 * webhooks). Las dos mitades comparten el esquema de subdominio {@code <slug>.<dominio-plataforma>} y
 * tienen que seguir de acuerdo: si una compone lo que la otra no sabe descomponer, los enlaces dejan de
 * volver.
 *
 * <p><b>Qué había antes.</b> Una sola propiedad {@code app.frontend-url} para todas las tiendas, inyectada
 * en siete clases. En un sistema multi-tienda eso significa que el comprador de cualquier tienda acababa
 * en el host de una sola, y que los tres correos con enlace —reset de contraseña de administrador,
 * invitación de administrador e invitación de cliente— llevaban a esa misma. El caso más duro era Webpay,
 * cuya redirección tras el pago no era sobreescribible por el cliente: era siempre la global. Y los
 * respaldos de esa propiedad ni siquiera apuntaban a rutas que existieran ({@code /order/success} en
 * Stripe cuando la SPA tiene {@code /order-success}; {@code /checkout/webpay/return} en Webpay, que no es
 * ninguna ruta de la SPA porque el retorno lo recibe el backend).
 *
 * <p><b>Y las que se armaban desde la petición en curso</b> ({@code return_url} de Webpay,
 * {@code notification_url} de MercadoPago, con {@code getScheme()}/{@code getServerName()}/
 * {@code getServerPort()}) resolvían la tienda correcta pero describían el tramo interno: detrás de un
 * proxy que termina TLS el esquema es {@code http} y el puerto es el del upstream, así que en la pasarela
 * quedaba registrada una URL de la red privada. No se arregla activando
 * {@code server.forward-headers-strategy}: {@code ClientIpResolver} (F18) documenta que
 * {@code ForwardedHeaderFilter} no tiene noción de proxy de confianza y extendería el agujero.
 *
 * <p><b>Recibe la tienda por parámetro y no lee {@code TenantContext} ni la petición.</b> Es lo que le
 * permite funcionar en los oyentes {@code AFTER_COMMIT} y en los trabajos programados, que corren en hilos
 * de pool sin petición HTTP y toman la tienda de la orden o del usuario.
 */
@Component
public class StorePublicUrlResolver {

    private static final String HOST_PLACEHOLDER = "{host}";
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final String platformDomain;
    private final String storefrontTemplate;
    private final String apiTemplate;

    public StorePublicUrlResolver(
            @Value("${app.public-url.platform-domain}") String platformDomain,
            @Value("${app.public-url.storefront}") String storefrontTemplate,
            @Value("${app.public-url.api}") String apiTemplate) {
        this.platformDomain = normalizePlatformDomain(platformDomain);
        this.storefrontTemplate = normalizeTemplate("app.public-url.storefront", storefrontTemplate);
        this.apiTemplate = normalizeTemplate("app.public-url.api", apiTemplate);
    }

    /** Donde vive el storefront de esta tienda: lo que va en un correo o en una redirección al navegador. */
    public String storefrontOrigin(Store store) {
        return storefrontTemplate.replace(HOST_PLACEHOLDER, hostname(store));
    }

    /**
     * Donde vive la API de esta tienda: lo que se le entrega a una pasarela para que nos llame de vuelta.
     *
     * <p>En producción coincide con el storefront —un solo origen tras el proxy inverso— y en desarrollo no,
     * porque Vite y el backend escuchan en puertos distintos. Esa diferencia vive entera en la
     * configuración y no en el código.
     */
    public String apiOrigin(Store store) {
        return apiTemplate.replace(HOST_PLACEHOLDER, hostname(store));
    }

    public String storefrontUrl(Store store, String path) {
        return join(storefrontOrigin(store), path);
    }

    public String apiUrl(Store store, String path) {
        return join(apiOrigin(store), path);
    }

    /**
     * El nombre de host por el que esta tienda es alcanzable de cara al público.
     *
     * <p>El dominio propio solo se usa cuando está <b>verificado</b> (V23). Un dominio escrito por el
     * operador antes de que su DNS y su certificado apunten aquí es exactamente el caso en el que mandar un
     * correo con ese enlace lo convierte en un correo inservible — y una invitación no se puede reenviar
     * todavía. Mientras no esté verificado se usa el subdominio de plataforma, que funciona siempre.
     *
     * <p>Al revés que la resolución de entrada: {@code StoreHostResolver} sigue aceptando el dominio sin
     * verificar, porque si no nunca se podría comprobar que funciona antes de marcarlo.
     */
    private String hostname(Store store) {
        if (store == null) {
            // Un error de programación, no una entrada inválida: cae al catch-all como manda M1.
            throw new IllegalStateException("No se puede construir una URL pública sin tienda");
        }
        String domain = store.getDomain();
        if (domain != null && !domain.isBlank() && store.getDomainVerifiedAt() != null) {
            return domain;
        }
        return store.getSlug() + "." + platformDomain;
    }

    private static String join(String origin, String path) {
        if (path == null || path.isBlank()) {
            return origin;
        }
        return path.startsWith("/") ? origin + path : origin + "/" + path;
    }

    private static String normalizePlatformDomain(String value) {
        String domain = value == null ? "" : value.trim().toLowerCase();
        if (domain.isBlank()) {
            throw new IllegalStateException(
                    "app.public-url.platform-domain no puede estar vacía: es el dominio bajo el que cada tienda "
                            + "tiene su subdominio <slug>.<dominio>, y sin ella no se puede construir ningún enlace.");
        }
        if (domain.contains("://") || domain.contains("/") || domain.contains(":")) {
            throw new IllegalStateException(
                    "app.public-url.platform-domain debe ser solo el dominio, sin esquema ni puerto ni ruta: '"
                            + value + "'. El esquema y el puerto van en app.public-url.storefront/api.");
        }
        return domain;
    }

    /**
     * Las plantillas son lo que absorbe la diferencia entre ambientes, así que se validan al arrancar: una
     * plantilla mal escrita no da un fallo visible en el momento, da enlaces rotos en correos que ya
     * salieron. Mismo criterio que {@code ReportZone} con la zona horaria y que {@code SettingValues} con
     * los ajustes de dinero.
     */
    private static String normalizeTemplate(String propertyName, String value) {
        String template = value == null ? "" : value.trim();
        if (!template.contains(HOST_PLACEHOLDER)) {
            throw new IllegalStateException(propertyName + " debe contener el marcador " + HOST_PLACEHOLDER
                    + ", que es donde se sustituye el host de cada tienda. Valor recibido: '" + value + "'.");
        }
        // Se quita la barra final para que join() no produzca "//".
        while (template.endsWith("/")) {
            template = template.substring(0, template.length() - 1);
        }
        URI probe;
        try {
            probe = new URI(template.replace(HOST_PLACEHOLDER, "tienda-de-prueba.example"));
        } catch (URISyntaxException e) {
            throw new IllegalStateException(propertyName + " no es una URL válida: '" + value + "'.", e);
        }
        if (probe.getScheme() == null || !ALLOWED_SCHEMES.contains(probe.getScheme().toLowerCase())) {
            throw new IllegalStateException(propertyName + " debe empezar por http:// o https://, y ser absoluta: '"
                    + value + "'.");
        }
        if (probe.getPath() != null && !probe.getPath().isEmpty()) {
            throw new IllegalStateException(propertyName + " es un origen, no una URL con ruta: '" + value
                    + "'. Las rutas las añade quien construye cada enlace.");
        }
        return template;
    }
}
