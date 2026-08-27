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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.uvo.uvostore.security.JwtAuthenticationFilter;
import org.uvo.uvostore.security.PlatformApiKeyAuthFilter;
import org.uvo.uvostore.security.PosApiKeyAuthFilter;
import org.uvo.uvostore.security.PosWebhookAuthFilter;
import org.uvo.uvostore.security.TenantResolutionFilter;

import java.util.List;

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
            "/health",
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
            TenantResolutionFilter tenantResolutionFilter,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            PosWebhookAuthFilter posWebhookAuthFilter,
            PosApiKeyAuthFilter posApiKeyAuthFilter,
            PlatformApiKeyAuthFilter platformApiKeyAuthFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
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
                .addFilterBefore(platformApiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
