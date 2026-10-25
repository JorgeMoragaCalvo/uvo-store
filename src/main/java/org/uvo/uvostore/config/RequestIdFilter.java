package org.uvo.uvostore.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * PROD-18. Un id por petición: va en la cabecera {@value #HEADER} de la respuesta, en cada línea de log de esa
 * petición ({@code logging.pattern.correlation}) y en {@code audit_events.request_id}. Con eso, una fila de auditoría
 * lleva a los logs de la petición que la escribió, y un usuario que reporta un error puede dar el id que vio.
 *
 * <p>PROD-15b: el mismo id sigue a la venta hasta el POS. {@code OutboxService} lo guarda en la fila al encolar y
 * {@code PosClient} lo manda como {@value #HEADER} al entregarla (ver {@link LogContext}).
 *
 * <p>Acepta el id que llegue si tiene un formato acotado (un proxy, el POS o el navegador pueden traer el suyo para
 * seguir la petición entre sistemas); si no, genera uno. Un id mal formado se reemplaza, no se rechaza: es una ayuda
 * para correlacionar, no una prueba de nada, y cualquiera puede elegirlo.
 *
 * <p>Nunca toca la cabecera de la <b>petición</b>: {@code MercadoPagoController} lee el {@code x-request-id} que manda
 * MercadoPago (es el mismo nombre) para verificar su firma.
 *
 * <p>No es un {@code @Component}: se registra a mano en {@link FilterRegistrationConfig}, delante de todo, incluida la
 * cadena de Spring Security, para que también un 401 o un 429 lleven el id.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = LogContext.REQUEST_ID;

    // 8 a 64 caracteres, sin espacios ni saltos de línea (no puede inyectar líneas en el log). Cabe un UUID y el
    // $request_id de nginx.
    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{7,63}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = acceptedOrNew(request.getHeader(HEADER));
        // Antes de la cadena: cuando la respuesta se confirma (un error escrito a mano por un filtro), ya no se le
        // pueden agregar cabeceras.
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // remove y no clear: la tienda (PROD-15b) la pone y la quita TenantContext, no este filtro.
            MDC.remove(MDC_KEY);
        }
    }

    static String acceptedOrNew(String incoming) {
        if (incoming != null && ACCEPTED.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }
}
