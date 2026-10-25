package org.uvo.uvostore.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.uvo.uvostore.entity.tenant.Store;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

// Resolves which Store a request belongs to from the Host header. The hostname -> Store lookup
// itself lives in StoreHostResolver, shared with the CORS allowlist (A2).
//
// Leaves TenantContext empty (not an error) when nothing resolves — routes that don't need a
// tenant this way (POS sync/webhooks, which resolve their store via companyId instead;
// actuator/health; swagger; platform onboarding, which creates the store) simply never read
// TenantContext, so they're unaffected.
//
// G5: es también donde se rechaza una tienda suspendida (y desde PROD-23, una cerrada). El rechazo
// va aquí y no en StoreHostResolver a propósito: el resolver tiene que seguir devolviendo la tienda
// para poder decir *cuál* está suspendida. Filtrarla allí la volvería indistinguible de un dominio
// que no existe (400 "No se pudo determinar la tienda"), que es el mensaje equivocado y complica el
// diagnóstico.
@Component
public class TenantResolutionFilter extends OncePerRequestFilter {

    /**
     * PROD-23. Por donde vuelve un pago ya hecho: el retorno del navegador desde Transbank y los avisos de
     * MercadoPago y Stripe. Pasan aunque la tienda esté suspendida, para que un comprador que pagó justo antes de
     * la suspensión no se quede con el cargo y sin la orden. Solo POST y solo estas rutas exactas: crear un pago
     * nuevo ({@code /webpay/create}, {@code /create-preference}...) sigue bloqueado.
     */
    private static final Set<String> PAYMENT_CALLBACKS = Set.of(
            "/api/v1/webpay/return",
            "/api/v1/mercadopago/webhook",
            "/api/v1/stripe/webhook");

    private final StoreHostResolver storeHostResolver;

    public TenantResolutionFilter(StoreHostResolver storeHostResolver) {
        this.storeHostResolver = storeHostResolver;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        try {
            Optional<Store> store = storeHostResolver.resolve(request.getHeader("Host"));
            store.ifPresent(TenantContext::set);

            if (store.isPresent() && !store.get().isActive() && !isPlatformRoute(request)
                    && !(store.get().isSuspended() && isPaymentCallback(request))) {
                reject(response, store.get());
                return;
            }

            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    // /api/platform/** es por donde el operador reactiva la tienda, así que no puede quedar del lado
    // bloqueado. Decisión explícita: todo lo demás sí se bloquea, incluido el panel de administración
    // de esa tienda. Dejar entrar al comerciante a ver por qué está suspendido necesitaría una
    // pantalla de facturación que hoy no existe.
    private static boolean isPlatformRoute(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/platform/");
    }

    // PROD-23. Una tienda cerrada no recibe ni esto: ya no tiene credenciales de pasarela con las que
    // confirmar nada, y el cierre exige que no quede ningún pago pendiente.
    private static boolean isPaymentCallback(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && PAYMENT_CALLBACKS.contains(request.getRequestURI());
    }

    private static void reject(HttpServletResponse response, Store store) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(store.isClosed()
                ? "{\"message\":\"Esta tienda está cerrada\"}"
                : "{\"message\":\"Esta tienda está suspendida\"}");
    }
}
