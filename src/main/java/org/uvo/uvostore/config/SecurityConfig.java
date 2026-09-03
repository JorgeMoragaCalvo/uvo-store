package org.uvo.uvostore.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.uvo.uvostore.security.JwtAuthenticationFilter;
import org.uvo.uvostore.security.PlatformApiKeyAuthFilter;
import org.uvo.uvostore.security.PosApiKeyAuthFilter;
import org.uvo.uvostore.security.PosWebhookAuthFilter;
import org.uvo.uvostore.security.RateLimitFilter;
import org.uvo.uvostore.security.TenantResolutionFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/api/v1/**",
            "/api/admin/auth/**",
            "/api/customer/auth/**",
            "/api/webhooks/pos/**",
            "/api/sync/**",
            "/api/platform/**",
            "/uploads/**",
            // A3: "/health" used to be here, but nothing ever served that path — actuator's real
            // endpoint is /actuator/health, and that one wasn't public. A liveness/readiness probe
            // got a 404 on one and a 401 on the other. Exposure is pinned to `health` alone in
            // application.properties, so opening this doesn't open the rest of actuator.
            "/actuator/health",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            TenantCorsConfigurationSource corsConfigurationSource,
            RateLimitFilter rateLimitFilter,
            TenantResolutionFilter tenantResolutionFilter,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            PosWebhookAuthFilter posWebhookAuthFilter,
            PosApiKeyAuthFilter posApiKeyAuthFilter,
            PlatformApiKeyAuthFilter platformApiKeyAuthFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                // A8: explicit rather than relying on Spring Security's default, because it's the
                // header that stops a browser from second-guessing the content type of anything
                // under /uploads/** — public files served from this same origin — and rendering it
                // as HTML. This chain covers static resources too, so it applies there.
                .headers(headers -> headers.contentTypeOptions(contentType -> {}))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // POS webhook/sync routes authenticate themselves via HMAC signature /
                        // API-key checks (PosWebhookAuthFilter/PosApiKeyAuthFilter below), not
                        // Spring Security's own authorities — permitAll here on purpose, same
                        // as the public v1 API.
                        .requestMatchers("/api/admin/**").hasAuthority("ROLE_ADMIN")
                        .requestMatchers("/api/customer/**").hasAuthority("ROLE_CUSTOMER")
                        .anyRequest().authenticated()
                )
                // JwtAuthenticationFilter must be registered (added relative to a known filter)
                // before TenantResolutionFilter can be anchored "before" it.
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // TenantResolutionFilter must run before JwtAuthenticationFilter so
                // TenantContext is populated in time for the token/subdomain cross-check.
                .addFilterBefore(tenantResolutionFilter, JwtAuthenticationFilter.class)
                .addFilterBefore(posWebhookAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(posApiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(platformApiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // A4: first in the chain on purpose — a throttled request should be rejected before
                // resolving a tenant or parsing a JWT, so flooding costs the server as little as
                // possible.
                .addFilterBefore(rateLimitFilter, TenantResolutionFilter.class);

        return http.build();
    }
}
