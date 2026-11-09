package org.uvo.uvostore.config;

import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

// Swagger UI at /swagger-ui.html, spec at /v3/api-docs — both paths are in
// SecurityConfig.PUBLIC_PATHS. Grouped by the project's five route surfaces (see CLAUDE.md)
// instead of one flat list of 36+ controllers.
//
// PROD-17c: todo esto está detrás de SWAGGER_ENABLED (app.swagger.enabled). Apagado, estos beans se
// siguen creando pero springdoc no publica nada: ni la UI ni /v3/api-docs ni los grupos.
//
// GAP-17: como producción exige apagarlo, el contrato vigente es el exportado en docs/api/, que
// OpenApiSpecTest regenera y vigila. Cambiar un endpoint o un DTO expuesto obliga a regenerarlo.
@Configuration
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Token JWT emitido por /api/admin/auth/login o /api/customer/auth/login — "
                + "compartido por las superficies admin y cliente (JwtAuthenticationFilter)."
)
@SecurityScheme(
        name = "platformApiKey",
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.HEADER,
        paramName = "X-Platform-Key",
        description = "Clave compartida del operador de la plataforma, usada solo por /api/platform/** "
                + "(alta de tiendas). No es una credencial de cliente."
)
public class OpenApiConfig {

    // GAP-17. Sin un servidor declarado, springdoc arma uno con el host de la petición, y la spec
    // exportada cambiaría según desde dónde se genere. Cada tienda es su propio host: la API va siempre
    // al mismo origen que la sirve.
    @Bean
    public OpenAPI uvoStoreOpenApi() {
        return new OpenAPI()
                .servers(List.of(new Server().url("/").description("Mismo origen de la tienda")))
                .info(new Info()
                .title("UvoStore API")
                .version("0.0.1-SNAPSHOT")
                .description("""
                        API de UvoStore, una plataforma SaaS de e-commerce multi-tenant. Agrupa cinco \
                        superficies con distinta autenticación:

                        - **Pública** (`/api/v1/**`): catálogo, carrito y checkout del storefront, sin autenticación.
                        - **Admin** (`/api/admin/**`): panel de administración de cada tienda, JWT bearer con rol ADMIN.
                        - **Cliente** (`/api/customer/**`): cuenta y direcciones del comprador, JWT bearer con rol CUSTOMER.
                        - **POS** (`/api/webhooks/pos/**`, `/api/sync/**`): integración con UvoPOS, firma HMAC \
                        (`X-Signature`/`X-Company-ID`/`X-Timestamp`) o API key — no probable con el botón \
                        "Authorize" de esta UI, ver la documentación de cada endpoint.
                        - **Plataforma** (`/api/platform/**`): alta y ciclo de vida de las tiendas por el equipo \
                        operador, header `X-Platform-Key`, solo desde la red privada.
                        """));
    }

    @Bean
    public GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder().group("public").displayName("Pública (storefront)")
                .pathsToMatch("/api/v1/**").build();
    }

    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder().group("admin").displayName("Admin")
                .pathsToMatch("/api/admin/**").build();
    }

    @Bean
    public GroupedOpenApi customerApi() {
        return GroupedOpenApi.builder().group("customer").displayName("Cliente")
                .pathsToMatch("/api/customer/**").build();
    }

    @Bean
    public GroupedOpenApi posApi() {
        return GroupedOpenApi.builder().group("pos").displayName("POS")
                .pathsToMatch("/api/webhooks/pos/**", "/api/sync/**").build();
    }

    @Bean
    public GroupedOpenApi platformApi() {
        return GroupedOpenApi.builder().group("platform").displayName("Plataforma")
                .pathsToMatch("/api/platform/**").build();
    }
}
