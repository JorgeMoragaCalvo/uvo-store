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
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * PROD-07. Con las cuentas de comprador diferidas, todo {@code /api/customer/**} responde 404.
 *
 * <p><b>Por prefijo y no endpoint por endpoint</b>, que es lo que lo hace duradero: cubre los quince
 * endpoints de hoy y también el que alguien añada mañana bajo ese prefijo sin acordarse de la bandera.
 * {@code @ConditionalOnProperty} sobre los controladores no alcanzaba: los tres endpoints de auth del
 * comprador viven dentro de {@code AuthController}, junto a los siete de administración, y esa clase no
 * tiene {@code @RequestMapping} de clase.
 *
 * <p><b>404 y no 401 ni 503.</b> La respuesta honesta es que ahí no hay nada. Un 401 pediría
 * credenciales para algo que no existe, y además es el código con el que la SPA cierra sesión; un 503
 * diría "vuelve más tarde", que es una promesa sobre una fecha que no hay.
 *
 * <p><b>Dónde corre.</b> Después de {@link RateLimitFilter} y antes de {@link TenantResolutionFilter}
 * ({@code SecurityConfig}). Una llamada apagada no resuelve tienda ni parsea JWT —no hace falta para
 * decir que no hay nada— pero sigue contando para el límite de intentos: una función diferida no debe
 * convertirse en un endpoint gratis e ilimitado. Y por ir antes de la cadena de Security, el 404 llega
 * igual sin credencial, que es la diferencia observable con el 401 de {@link ApiSecurityErrorHandlers}.
 *
 * <p>El cuerpo se serializa con {@link ApiError} y el {@code ObjectMapper} del contexto, como
 * {@code ApiSecurityErrorHandlers.write} y no como {@code RateLimitFilter.reject}, que arma el JSON
 * concatenando y por eso le faltan {@code timestamp} y {@code fieldErrors}. Ojo: el bean es Jackson 3
 * ({@code tools.jackson.databind}, el de Boot 4); los {@code new ObjectMapper()} sueltos del proyecto
 * son Jackson 2 y no son el que está en el contexto.
 */
@Component
public class CustomerAccountsGateFilter extends OncePerRequestFilter {

    /**
     * Sin barra final a propósito, para que {@code /api/customer} a secas tampoco se escape.
     */
    private static final String CUSTOMER_PREFIX = "/api/customer";

    private final CustomerAccountsPolicy accountsPolicy;
    private final ObjectMapper objectMapper;

    public CustomerAccountsGateFilter(CustomerAccountsPolicy accountsPolicy, ObjectMapper objectMapper) {
        this.accountsPolicy = accountsPolicy;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (accountsPolicy.isEnabled() || !request.getRequestURI().startsWith(CUSTOMER_PREFIX)) {
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
