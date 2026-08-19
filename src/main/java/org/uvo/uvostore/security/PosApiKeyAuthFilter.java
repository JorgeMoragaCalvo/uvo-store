package org.uvo.uvostore.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.repository.PosConnectionRepository;

import java.io.IOException;
import java.util.Optional;

// Ports AuthenticatePosApi middleware: Bearer API key + X-Company-ID header, matched against
// PosConnection.apiKey. Only guards /api/sync/** — every other path is passed through untouched.
@Component
public class PosApiKeyAuthFilter extends OncePerRequestFilter {

    private final PosConnectionRepository posConnectionRepository;

    public PosApiKeyAuthFilter(PosConnectionRepository posConnectionRepository) {
        this.posConnectionRepository = posConnectionRepository;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/sync/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            reject(response, "MISSING_API_KEY", "API key no proporcionada");
            return;
        }
        String token = authorization.substring("Bearer ".length());

        String companyId = request.getHeader("X-Company-ID");
        if (companyId == null) {
            reject(response, "MISSING_COMPANY_ID", "Company ID no proporcionado");
            return;
        }

        Optional<PosConnection> connection;
        try {
            connection = posConnectionRepository.findByCompanyId(Long.valueOf(companyId)).filter(PosConnection::isActive);
        } catch (NumberFormatException e) {
            connection = Optional.empty();
        }
        if (connection.isEmpty()) {
            reject(response, "POS_CONNECTION_NOT_FOUND", "Conexión POS no encontrada o inactiva");
            return;
        }
        if (!connection.get().getApiKey().equals(token)) {
            reject(response, "INVALID_API_KEY", "API key inválida");
            return;
        }

        request.setAttribute("posConnection", connection.get());
        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String error, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\",\"error\":\"" + error + "\"}");
    }
}
