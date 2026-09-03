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
// Fixed-window counters keyed by client IP + rule. Deliberately not per-account: keying on the
// submitted email would mean reading the request body inside a filter, and the attacks this
// addresses are per-origin anyway. Per-account throttling is a separate, later concern.
//
// Storage is a Caffeine cache rather than a map so entries expire and the total is capped —
// otherwise the limiter itself would be the memory-exhaustion vector, since the key is attacker
// controlled.
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
    private final Cache<String, Window> windows = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    public RateLimitFilter(
            @Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.login:5}") int loginLimit,
            @Value("${app.rate-limit.register:5}") int registerLimit,
            @Value("${app.rate-limit.forgot-password:3}") int forgotPasswordLimit,
            @Value("${app.rate-limit.track:20}") int trackLimit,
            @Value("${app.rate-limit.window-seconds:60}") int windowSeconds) {
        this.enabled = enabled;
        Duration window = Duration.ofSeconds(windowSeconds);
        this.rules = List.of(
                new Rule("POST", "/api/admin/auth/login", loginLimit, window),
                new Rule("POST", "/api/customer/auth/login", loginLimit, window),
                new Rule("POST", "/api/customer/auth/register", registerLimit, window),
                // Tighter and over a longer window: each hit sends a real email to an address the
                // caller chooses, so this is the mail-bombing lever.
                new Rule("POST", "/api/admin/auth/forgot-password", forgotPasswordLimit, window.multipliedBy(5)),
                new Rule("GET", "/api/v1/orders/track", trackLimit, window));
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

        String key = clientIp(request) + " " + rule.method() + " " + rule.path();
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

    // X-Forwarded-For first: behind a reverse proxy getRemoteAddr() is the proxy's own address, so
    // every caller would share one bucket. Only the first hop is used; the rest of the chain is
    // client-supplied and worthless. With no proxy in front, the header is absent and this falls
    // back to the socket address.
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
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
