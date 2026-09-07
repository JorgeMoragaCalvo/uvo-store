package org.uvo.uvostore.config;

import jakarta.servlet.Filter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.uvo.uvostore.security.JwtAuthenticationFilter;
import org.uvo.uvostore.security.PlatformApiKeyAuthFilter;
import org.uvo.uvostore.security.PosApiKeyAuthFilter;
import org.uvo.uvostore.security.PosWebhookAuthFilter;
import org.uvo.uvostore.security.RateLimitFilter;
import org.uvo.uvostore.security.TenantResolutionFilter;

/**
 * B8: los seis filtros de seguridad son {@code @Component}, y Spring Boot registra automáticamente
 * como filtro de servlet cualquier bean de tipo {@link Filter}. Resultado: cada uno estaba
 * registrado dos veces — una en la cadena del servlet y otra donde de verdad se le quiso poner,
 * dentro de la cadena de Spring Security.
 *
 * <p>No es lo que decía el hallazgo, que daba por hecho que el orden real no era el de
 * {@code SecurityConfig}. Sí lo es: la cadena de Security se registra con orden {@code -100} y los
 * filtros autorregistrados quedan en {@code LOWEST_PRECEDENCE}, así que Security corre primero y,
 * cuando le toca el turno al registro automático, {@link
 * org.springframework.web.filter.OncePerRequestFilter} ya marcó la petición y los salta.
 *
 * <p>Lo que sí queda mal sin esto es todo lo que no pasa por la cadena de Security: los
 * {@code DispatcherType.ERROR}, los recursos estáticos y cualquier petición que la cadena no
 * atienda. Ahí los seis corren de verdad, en un orden que nadie declaró — y ese orden importa,
 * porque {@code TenantResolutionFilter} tiene que resolver la tienda antes de que
 * {@code JwtAuthenticationFilter} valide un token contra ella, y {@code RateLimitFilter} tiene que
 * ir antes que ambos para que un ataque no pague el costo de resolver la tienda.
 *
 * <p>Desactivar el registro automático deja a {@code SecurityConfig} como el único lugar donde se
 * decide el orden, que es donde alguien lo va a buscar.
 */
@Configuration
public class FilterRegistrationConfig {

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        return disableAutoRegistration(filter);
    }

    @Bean
    public FilterRegistrationBean<TenantResolutionFilter> tenantResolutionFilterRegistration(TenantResolutionFilter filter) {
        return disableAutoRegistration(filter);
    }

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(JwtAuthenticationFilter filter) {
        return disableAutoRegistration(filter);
    }

    @Bean
    public FilterRegistrationBean<PosWebhookAuthFilter> posWebhookAuthFilterRegistration(PosWebhookAuthFilter filter) {
        return disableAutoRegistration(filter);
    }

    @Bean
    public FilterRegistrationBean<PosApiKeyAuthFilter> posApiKeyAuthFilterRegistration(PosApiKeyAuthFilter filter) {
        return disableAutoRegistration(filter);
    }

    @Bean
    public FilterRegistrationBean<PlatformApiKeyAuthFilter> platformApiKeyAuthFilterRegistration(PlatformApiKeyAuthFilter filter) {
        return disableAutoRegistration(filter);
    }

    private static <T extends Filter> FilterRegistrationBean<T> disableAutoRegistration(T filter) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
