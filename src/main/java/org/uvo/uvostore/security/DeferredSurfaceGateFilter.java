package org.uvo.uvostore.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.uvo.uvostore.controller.advice.ApiError;
import org.uvo.uvostore.service.customer.CustomerAccountsPolicy;
import org.uvo.uvostore.service.pos.PosIntegrationPolicy;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Las superficies de API que esta instalación no sirve responden 404.
 *
 * <p>Nació en PROD-07 como {@code CustomerAccountsGateFilter} para las cuentas de comprador y PROD-12 le
 * añadió la superficie de entrada del POS. Un filtro con una lista de reglas, y no dos clases idénticas
 * salvo una cadena: la duplicación es como las dos se separan, y este repositorio ya la ha pagado tres
 * veces (los tres lectores de {@code ttl-days} que juntó {@code AdminInvitationPolicy}, los tres de
 * {@code tax_rate} que juntó {@code SettingValues}, las dos copias de la revocación que juntó F22). El
 * molde de la lista es el {@code Rule} de {@link RateLimitFilter}.
 *
 * <p><b>Por prefijo y no endpoint por endpoint</b>, que es lo que lo hace duradero: cubre los endpoints
 * de hoy y también el que alguien añada mañana bajo ese prefijo sin acordarse de la bandera.
 * {@code @ConditionalOnProperty} sobre los controladores no alcanzaba para las cuentas: los tres
 * endpoints de auth del comprador viven dentro de {@code AuthController}, junto a los de administración,
 * y esa clase no tiene {@code @RequestMapping} de clase.
 *
 * <p><b>404 y no 401 ni 503.</b> La respuesta honesta es que ahí no hay nada. Un 401 pediría credenciales
 * para algo que no existe, y además es el código con el que la SPA cierra sesión; un 503 diría "vuelve
 * más tarde", que es una promesa sobre una fecha que no hay.
 *
 * <p><b>Dónde corre.</b> Después de {@link RateLimitFilter} y antes de {@link TenantResolutionFilter}
 * ({@code SecurityConfig}). Una llamada apagada no resuelve tienda ni parsea JWT —no hace falta para
 * decir que no hay nada— pero sigue contando para el límite de intentos: una función diferida no debe
 * convertirse en un endpoint gratis e ilimitado. Y por ir antes de la cadena de Security, el 404 llega
 * igual sin credencial, que es la diferencia observable con el 401 de {@link ApiSecurityErrorHandlers}.
 *
 * <p>Las reglas se evalúan con la política de plataforma, no por tienda: aquí todavía no hay
 * {@code TenantContext}, y es correcto que no lo haya — el POS tiene además su interruptor por tienda,
 * que miran {@code PosOrderNotifier} y compañía, ya con la tienda en la mano.
 *
 * <p>El cuerpo se serializa con {@link ApiError} y el {@code ObjectMapper} del contexto, como
 * {@code ApiSecurityErrorHandlers.write} y no como {@code RateLimitFilter.reject}, que arma el JSON
 * concatenando y por eso le faltan {@code timestamp} y {@code fieldErrors}. Ojo: el bean es Jackson 3
 * ({@code tools.jackson.databind}, el de Boot 4); los {@code new ObjectMapper()} sueltos del proyecto
 * son Jackson 2 y no son el que está en el contexto.
 */
@Component
public class DeferredSurfaceGateFilter extends OncePerRequestFilter {

    /**
     * Un prefijo de API y la bandera que decide si se sirve. Los prefijos van <b>sin barra final</b> a
     * propósito, para que {@code /api/customer} a secas tampoco se escape.
     */
    private record Rule(String prefix, BooleanSupplier enabled) {

        boolean blocks(String uri) {
            return uri.startsWith(prefix) && !enabled.getAsBoolean();
        }
    }

    private final List<Rule> rules;
    private final ObjectMapper objectMapper;

    public DeferredSurfaceGateFilter(CustomerAccountsPolicy accountsPolicy, PosIntegrationPolicy posPolicy,
                                     ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.rules = List.of(
                // PROD-07: cuentas de comprador. La API está completa y el storefront no tiene ninguna
                // pantalla, así que lo honesto es no ofrecerla.
                new Rule("/api/customer", accountsPolicy::isEnabled),
                // PROD-12: las dos puntas de entrada del POS. Con la integración apagada esta superficie
                // no se sirve. Es cinturón —sin PosConnection el filtro de firma ya rechazaría— y es lo
                // que hace que "la integración está apagada" sea cierto por cualquier camino, no solo
                // por el de la salida.
                new Rule("/api/sync", posPolicy::isEnabled),
                new Rule("/api/webhooks/pos", posPolicy::isEnabled));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (rules.stream().noneMatch(rule -> rule.blocks(uri))) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.NOT_FOUND.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // El mismo mensaje que GlobalExceptionHandler da para un recurso que no existe: desde fuera
        // esto es indistinguible de una ruta que nunca se escribió, que es exactamente lo que se quiere
        // decir.
        objectMapper.writeValue(response.getWriter(),
                ApiError.of(HttpStatus.NOT_FOUND.value(), "Not Found", "El recurso solicitado no existe."));
    }
}
