package org.uvo.uvostore.url;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.service.url.StorePublicUrlResolver;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PROD-04. Las reglas de la dirección pública de una tienda.
 *
 * <p>Unitario y sin contexto de Spring: lo que decide este componente son tres propiedades y dos campos de
 * la tienda, y así los casos de configuración inválida se pueden afirmar uno por uno en vez de pedir un
 * contexto por cada uno.
 */
class StorePublicUrlResolverTest {

    private final StorePublicUrlResolver resolver =
            new StorePublicUrlResolver("uvostore.cl", "https://{host}", "https://{host}");

    private static Store store(String slug, String domain, Instant verifiedAt) {
        return Store.builder().id(1L).name("Tienda").slug(slug).domain(domain)
                .domainVerifiedAt(verifiedAt).build();
    }

    @Test
    @DisplayName("Sin dominio propio, el subdominio de plataforma")
    void withoutACustomDomainItUsesThePlatformSubdomain() {
        Store store = store("juan", null, null);

        assertThat(resolver.storefrontOrigin(store)).isEqualTo("https://juan.uvostore.cl");
        assertThat(resolver.storefrontUrl(store, "/admin/login")).isEqualTo("https://juan.uvostore.cl/admin/login");
    }

    @Test
    @DisplayName("Con dominio propio verificado, el dominio propio")
    void withAVerifiedCustomDomainItUsesTheCustomDomain() {
        Store store = store("juan", "tiendadejuan.cl", Instant.now());

        assertThat(resolver.storefrontOrigin(store)).isEqualTo("https://tiendadejuan.cl");
    }

    @Test
    @DisplayName("Con dominio propio SIN verificar, se vuelve al subdominio")
    void anUnverifiedCustomDomainIsNotUsed() {
        // El caso que motiva la columna: el operador escribe el dominio en el alta y el DNS del cliente
        // todavía no apunta aquí. Si se usara, el primer correo que saliera —la invitación del dueño—
        // llevaría un enlace a ninguna parte, y no hay pantalla para reenviarlo.
        Store store = store("juan", "tiendadejuan.cl", null);

        assertThat(resolver.storefrontOrigin(store)).isEqualTo("https://juan.uvostore.cl");
    }

    @Test
    @DisplayName("El storefront y la API pueden vivir en orígenes distintos")
    void theStorefrontAndTheApiCanDiffer() {
        // Es el caso de desarrollo: Vite en 5173 y el backend en 8080. En producción las dos plantillas
        // son iguales, porque los sirve el mismo proxy.
        StorePublicUrlResolver dev =
                new StorePublicUrlResolver("localhost", "http://{host}:5173", "http://{host}:8080");
        Store store = store("demo", null, null);

        assertThat(dev.storefrontUrl(store, "/checkout")).isEqualTo("http://demo.localhost:5173/checkout");
        assertThat(dev.apiUrl(store, "/api/v1/webpay/return")).isEqualTo("http://demo.localhost:8080/api/v1/webpay/return");
    }

    @Test
    @DisplayName("La ruta se une con una sola barra, venga como venga")
    void pathsAreJoinedWithExactlyOneSlash() {
        Store store = store("juan", null, null);

        assertThat(resolver.storefrontUrl(store, "/a")).isEqualTo("https://juan.uvostore.cl/a");
        assertThat(resolver.storefrontUrl(store, "a")).isEqualTo("https://juan.uvostore.cl/a");
        assertThat(resolver.storefrontUrl(store, null)).isEqualTo("https://juan.uvostore.cl");
        // Una plantilla con barra final no puede producir "//": lo que viaja en un correo es lo que se ve.
        assertThat(new StorePublicUrlResolver("uvostore.cl", "https://{host}/", "https://{host}/")
                .storefrontUrl(store, "/a")).isEqualTo("https://juan.uvostore.cl/a");
    }

    @Test
    @DisplayName("Sin tienda no hay URL: es un error de programación, no una entrada inválida")
    void aNullStoreIsAProgrammingError() {
        assertThatThrownBy(() -> resolver.storefrontOrigin(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sin tienda");
    }

    // --- configuración inválida: el arranque se cae en vez de mandar enlaces rotos -----------------

    @Test
    @DisplayName("Una plantilla sin {host} no arranca")
    void aTemplateWithoutThePlaceholderFailsAtStartup() {
        // Sin el marcador, TODAS las tiendas compartirían una dirección — exactamente el defecto que
        // app.frontend-url tenía y que esto viene a cerrar. Es el fallo que más importa detectar.
        assertThatThrownBy(() -> new StorePublicUrlResolver("uvostore.cl", "https://uvostore.cl", "https://{host}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("{host}");
    }

    @Test
    @DisplayName("Una plantilla relativa o sin esquema válido no arranca")
    void aTemplateThatIsNotAnAbsoluteHttpUrlFailsAtStartup() {
        assertThatThrownBy(() -> new StorePublicUrlResolver("uvostore.cl", "{host}", "https://{host}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("http");
        assertThatThrownBy(() -> new StorePublicUrlResolver("uvostore.cl", "ftp://{host}", "https://{host}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("http");
    }

    @Test
    @DisplayName("Una plantilla con ruta no arranca: es un origen")
    void aTemplateWithAPathFailsAtStartup() {
        // Con una ruta dentro, cada enlace saldría con ella duplicada o en el sitio equivocado.
        assertThatThrownBy(() -> new StorePublicUrlResolver("uvostore.cl", "https://{host}/tienda", "https://{host}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ruta");
    }

    @Test
    @DisplayName("Un dominio de plataforma vacío, o con esquema o puerto, no arranca")
    void anInvalidPlatformDomainFailsAtStartup() {
        assertThatThrownBy(() -> new StorePublicUrlResolver("  ", "https://{host}", "https://{host}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vacía");
        assertThatThrownBy(() -> new StorePublicUrlResolver("https://uvostore.cl", "https://{host}", "https://{host}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sin esquema");
        assertThatThrownBy(() -> new StorePublicUrlResolver("uvostore.cl:8080", "https://{host}", "https://{host}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sin esquema");
    }
}
