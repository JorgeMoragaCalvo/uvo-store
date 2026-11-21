package org.uvo.uvostore.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * IMP-09. Con el modo recuperación encendido ({@link RecoveryModePolicy}), lo que podría tener un efecto fuera de la
 * base responde <b>503 {@code RECOVERY_MODE}</b> antes de llegar a ningún controlador:
 *
 * <ul>
 *   <li>toda escritura (lo que no es GET, HEAD u OPTIONS): el checkout, crear un pago, los webhooks de las pasarelas y
 *       del POS, el alta de productos desde el POS, cancelar o reembolsar desde el panel, el alta de tiendas…;</li>
 *   <li>y el retorno de Webpay también por GET, porque confirma el pago ({@code commit}).</li>
 * </ul>
 *
 * <p>503 y no 403: el proveedor que manda un webhook lo reintenta, y el evento llega cuando la instalación de verdad
 * vuelva a atender. Un 4xx lo daría por entregado.
 *
 * <p>Se deja pasar solo lo que sirve para conciliar sin mover nada fuera: el login del panel, la resolución a mano del
 * outbox y de los reembolsos inciertos (anotan, no envían: con el planificador apagado, un reintento solo vuelve a la
 * cola), el registro de un reembolso hecho por fuera, los correos de acceso de la API de plataforma (lo mismo) y el
 * informe de recuperación. El carrito ({@code validate}, {@code calculate}) es un cálculo sin escritura.
 *
 * <p>Va como filtro de servlet justo después de {@link RequestIdFilter}, fuera de la cadena de Spring Security, para
 * que nada de lo que hay detrás —ni la resolución de la tienda— llegue a correr.
 */
public class RecoveryModeFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /** Confirma el pago en Transbank aunque llegue por GET (GAP-19). */
    private static final Pattern ALWAYS_BLOCKED = Pattern.compile("^/api/v1/webpay/return/?$");

    private static final List<Pattern> ALLOWED_WRITES = List.of(
            Pattern.compile("^/api/admin/auth/(login|logout)$"),
            Pattern.compile("^/api/admin/outbox/\\d+/(retry|resolve)$"),
            Pattern.compile("^/api/admin/orders/\\d+/refunds/\\d+/resolve$"),
            Pattern.compile("^/api/admin/orders/\\d+/refund/external$"),
            Pattern.compile("^/api/platform/access-emails/\\d+/(retry|resolve)$"),
            Pattern.compile("^/api/platform/recovery/.+$"),
            Pattern.compile("^/api/v1/cart/(validate|calculate)$"));

    private final RecoveryModePolicy policy;

    public RecoveryModeFilter(RecoveryModePolicy policy) {
        this.policy = policy;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !policy.isActive();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (blocks(request.getMethod(), pathOf(request))) {
            reject(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    static boolean blocks(String method, String path) {
        if (ALWAYS_BLOCKED.matcher(path).matches()) {
            return true;
        }
        if (SAFE_METHODS.contains(method)) {
            return false;
        }
        return ALLOWED_WRITES.stream().noneMatch(pattern -> pattern.matcher(path).matches());
    }

    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        // Una hora: lo que tarde la conciliación. Es una pista para quien reintenta, no una promesa.
        response.setHeader("Retry-After", "3600");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // A mano y con el mismo formato que ApiError: aquí no hay ObjectMapper de Spring, y no hace falta.
        response.getWriter().write("{\"timestamp\":\"" + Instant.now() + "\",\"status\":503,\"error\":\""
                + RecoveryModeException.CODE + "\",\"message\":\"La tienda está en mantenimiento. Vuelve a "
                + "intentarlo más tarde.\",\"fieldErrors\":null}");
    }
}
