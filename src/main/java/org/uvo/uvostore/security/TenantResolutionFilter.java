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

// Resolves which Store a request belongs to from the Host header. The hostname -> Store lookup
// itself lives in StoreHostResolver, shared with the CORS allowlist (A2).
//
// Leaves TenantContext empty (not an error) when nothing resolves — routes that don't need a
// tenant this way (POS sync/webhooks, which resolve their store via companyId instead;
// actuator/health; swagger; platform onboarding, which creates the store) simply never read
// TenantContext, so they're unaffected.
//
// G5: es también donde se rechaza una tienda suspendida. El rechazo va aquí y no en
// StoreHostResolver a propósito: el resolver tiene que seguir devolviendo la tienda para poder
// decir *cuál* está suspendida. Filtrarla allí la volvería indistinguible de un dominio que no
// existe (400 "No se pudo determinar la tienda"), que es el mensaje equivocado y complica el
// diagnóstico.
@Component
public class TenantResolutionFilter extends OncePerRequestFilter {

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

            if (store.filter(Store::isSuspended).isPresent() && !isPlatformRoute(request)) {
                rejectSuspended(response);
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

    private static void rejectSuspended(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"Esta tienda está suspendida\"}");
    }
}
