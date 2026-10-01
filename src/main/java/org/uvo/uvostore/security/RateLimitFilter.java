package org.uvo.uvostore.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

// A4: login, registration, password recovery and order tracking were completely unthrottled —
// brute force, credential stuffing, mail bombing (every forgot-password hit sends an email) and
// order-number enumeration all had zero cost.
//
// Fixed-window counters keyed by client IP + rule. La IP la decide ClientIpResolver, no este filtro:
// F18 encontró que se tomaba la primera entrada de X-Forwarded-For sin mirar de quién venía, así que
// quien mandaba la cabecera elegía su propio contador y este control no existía.
//
// Esto cuenta por origen. El presupuesto por CUENTA —que es lo que sigue faltando cuando el atacante
// tiene muchas direcciones— vive en AccountAttemptThrottle, llamado desde AuthController, donde el
// correo ya está parseado: no hace falta leer el cuerpo dentro de un filtro, que era la razón por la que
// esta nota decía antes que el asunto quedaba para más adelante.
//
// Storage is a Caffeine cache rather than a map so entries expire and the total is capped. Con la clave
// falsificable eso era además lo único que contenía un segundo ataque: cien mil direcciones inventadas
// desalojaban los contadores de todo el mundo. Ya no se puede elegir la clave, pero el tope se queda.
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private record Rule(String method, String path, int limit, Duration window) {
        boolean matches(HttpServletRequest request) {
            return method.equals(request.getMethod()) && path.equals(request.getRequestURI());
        }
    }

    private record Window(Instant resetAt, AtomicInteger count) {
    }

    private final boolean enabled;
    private final List<Rule> rules;
    private final ClientIpResolver clientIpResolver;
    private final Cache<String, Window> windows = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    public RateLimitFilter(
            ClientIpResolver clientIpResolver,
            @Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.login:5}") int loginLimit,
            @Value("${app.rate-limit.register:5}") int registerLimit,
            @Value("${app.rate-limit.forgot-password:3}") int forgotPasswordLimit,
            @Value("${app.rate-limit.track:20}") int trackLimit,
            @Value("${app.rate-limit.webhook:60}") int webhookLimit,
            @Value("${app.rate-limit.window-seconds:60}") int windowSeconds) {
        this.clientIpResolver = clientIpResolver;
        this.enabled = enabled;
        Duration window = Duration.ofSeconds(windowSeconds);
        this.rules = List.of(
                new Rule("POST", "/api/admin/auth/login", loginLimit, window),
                new Rule("POST", "/api/customer/auth/login", loginLimit, window),
                new Rule("POST", "/api/customer/auth/register", registerLimit, window),
                // Tighter and over a longer window: each hit sends a real email to an address the
                // caller chooses, so this is the mail-bombing lever.
                new Rule("POST", "/api/admin/auth/forgot-password", forgotPasswordLimit, window.multipliedBy(5)),
                new Rule("GET", "/api/v1/orders/track", trackLimit, window),
                // M3: public, unauthenticated, and each accepted hit costs the merchant an outbound
                // MercadoPago API call. The signature check rejects forgeries; this caps the volume.
                new Rule("POST", "/api/v1/mercadopago/webhook", webhookLimit, window));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        Rule rule = rules.stream().filter(r -> r.matches(request)).findFirst().orElse(null);
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = clientIpResolver.resolve(request) + " " + rule.method() + " " + rule.path();
        Instant now = Instant.now();
        Window window = windows.asMap().compute(key, (k, existing) ->
                existing == null || now.isAfter(existing.resetAt())
                        ? new Window(now.plus(rule.window()), new AtomicInteger(0))
                        : existing);

        if (window.count().incrementAndGet() > rule.limit()) {
            reject(response, Duration.between(now, window.resetAt()));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, Duration retryAfter) throws IOException {
        long seconds = Math.max(1, retryAfter.getSeconds());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(seconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // Same shape as ApiError so a client parses it exactly like every other error response.
        response.getWriter().write("{\"status\":429,\"error\":\"Too Many Requests\","
                + "\"message\":\"Demasiadas solicitudes. Intenta nuevamente en " + seconds + " segundos.\"}");
    }
}
