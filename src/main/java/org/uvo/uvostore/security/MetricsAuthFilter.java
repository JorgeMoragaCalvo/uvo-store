package org.uvo.uvostore.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.uvo.uvostore.controller.advice.ApiError;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * PROD-15b. Guarda {@value #PATH}, las métricas en formato Prometheus. Va en el mismo puerto que la tienda, así que
 * tiene tres puertas, en este orden:
 * <ol>
 *   <li><b>Red</b>: fuera de la red privada ({@link PlatformNetworkPolicy}, la misma de la API de plataforma) es un
 *       404 idéntico al de una ruta que no existe, y la clave ni se mira.</li>
 *   <li><b>Configuración</b>: sin {@code METRICS_TOKEN} la ruta no existe (404). Encender las métricas es una
 *       decisión explícita del despliegue, no algo que venga abierto por defecto.</li>
 *   <li><b>Token</b>: {@code Authorization: Bearer <METRICS_TOKEN>}, comparado en tiempo constante; si falta o no
 *       coincide, 401. Es lo que Prometheus manda con {@code authorization.credentials} (o {@code bearer_token}).</li>
 * </ol>
 *
 * <p>Las métricas no llevan datos de una tienda ni de personas, pero sí el volumen del negocio y el estado de las
 * colas: no son para Internet. {@code /actuator/metrics} sigue sin exponerse.
 */
@Component
public class MetricsAuthFilter extends OncePerRequestFilter {

    public static final String PATH = "/actuator/prometheus";

    private static final Logger log = LoggerFactory.getLogger(MetricsAuthFilter.class);
    private static final String BEARER = "Bearer ";

    private final String token;
    private final PlatformNetworkPolicy networkPolicy;
    private final ObjectMapper objectMapper;

    public MetricsAuthFilter(@Value("${app.metrics.token:}") String token, PlatformNetworkPolicy networkPolicy,
                             ObjectMapper objectMapper) {
        this.token = token == null ? "" : token.trim();
        this.networkPolicy = networkPolicy;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (!PATH.equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!networkPolicy.allows(request)) {
            log.warn("Métricas: petición rechazada desde una red no permitida (socket={})", request.getRemoteAddr());
            notFound(response);
            return;
        }
        if (token.isEmpty()) {
            notFound(response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER)
                || !constantTimeEquals(token, authorization.substring(BEARER.length()))) {
            writeError(response, HttpStatus.UNAUTHORIZED, "Unauthorized", "Token de métricas inválido.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    // El mismo cuerpo que PlatformApiKeyAuthFilter y DeferredSurfaceGateFilter: desde fuera, una ruta que no existe.
    private void notFound(HttpServletResponse response) throws IOException {
        writeError(response, HttpStatus.NOT_FOUND, "Not Found", "El recurso solicitado no existe.");
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String error, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiError.of(status.value(), error, message));
    }
}
