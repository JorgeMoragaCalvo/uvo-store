package org.uvo.uvostore.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.service.TooManyRequestsException;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * F18, segunda mitad. Presupuesto por cuenta, no por origen.
 *
 * <p>{@link RateLimitFilter} cuenta por dirección IP, y eso es lo que sobrevive al arreglo de la
 * cabecera: con cinco intentos por minuto y por dirección, una botnet con mil direcciones sigue probando
 * miles de contraseñas por hora <b>contra la misma cuenta</b>. Esto pone un techo a lo que se puede
 * intentar contra una cuenta concreta, venga de donde venga.
 *
 * <p>Vive aquí y no en el filtro porque se llama desde {@code AuthController}, donde el correo ya está
 * parseado y validado. La nota de {@code RateLimitFilter} decía que hacerlo exigiría leer el cuerpo
 * dentro de un filtro; no hace falta — y de todos modos {@link CachedBodyHttpServletRequest} ya resuelve
 * eso en este mismo paquete desde M3.
 *
 * <p><b>La clave lleva el id de la tienda.</b> Los correos son por tienda
 * ({@code findByStoreIdAndEmail}), así que sin eso dos tiendas con el mismo correo compartirían
 * presupuesto y una podría agotarle el de la otra.
 *
 * <p><b>Se cuenta ocurra o no la cuenta.</b> Es la trampa de este control:
 * {@code adminForgotPassword} responde 200 siempre a propósito, para no revelar qué correos existen, y un
 * 429 que solo apareciera para los correos reales filtraría exactamente eso.
 *
 * <p><b>Consecuencia asumida</b>: alguien puede gastarle el presupuesto a una cuenta ajena y su dueño
 * vería 429 durante esa ventana. Se acota con un límite alto —una persona real no lo toca— y con que no
 * haya bloqueo persistente: nada que escribir en la base, nada que un administrador tenga que
 * desbloquear a mano. La ventana pasa y se puede volver a entrar.
 */
@Component
public class AccountAttemptThrottle {

    /** Para qué se cuenta. Dos flujos distintos no se roban el presupuesto entre ellos. */
    public enum Flow {
        ADMIN_LOGIN,
        CUSTOMER_LOGIN,
        ADMIN_FORGOT_PASSWORD
    }

    private record Window(Instant resetAt, AtomicInteger count) {
    }

    private final boolean enabled;
    private final int limit;
    private final Duration window;
    // Mismo patrón y misma razón que RateLimitFilter: expira y tiene techo, para que el control no sea él
    // mismo la vía de agotar memoria.
    private final Cache<String, Window> windows = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    public AccountAttemptThrottle(
            @Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.account-attempts:20}") int limit,
            @Value("${app.rate-limit.account-window-seconds:900}") int windowSeconds) {
        this.enabled = enabled;
        this.limit = limit;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    /**
     * Rechaza si esta cuenta ya agotó su presupuesto. No consume nada: en los logins lo que se cuenta es
     * el fallo, no el intento, así que esto va antes de comprobar la contraseña y
     * {@link #recordFailure} después.
     */
    public void check(Long storeId, Flow flow, String email) {
        if (!enabled) {
            return;
        }
        Window existing = windows.getIfPresent(key(storeId, flow, email));
        Instant now = Instant.now();
        if (existing != null && now.isBefore(existing.resetAt()) && existing.count().get() >= limit) {
            throw new TooManyRequestsException(
                    "Demasiados intentos para esta cuenta. Intenta nuevamente más tarde.",
                    Duration.between(now, existing.resetAt()));
        }
    }

    /** Un intento fallido, o —en «olvidé mi contraseña»— cualquier llamada, porque ahí el costo es el correo. */
    public void recordFailure(Long storeId, Flow flow, String email) {
        if (!enabled) {
            return;
        }
        Instant now = Instant.now();
        windows.asMap().compute(key(storeId, flow, email), (k, existing) -> {
            if (existing == null || now.isAfter(existing.resetAt())) {
                return new Window(now.plus(window), new AtomicInteger(1));
            }
            existing.count().incrementAndGet();
            return existing;
        });
    }

    /**
     * Entró: se borra el contador. Quien se equivoca de contraseña y luego acierta no arrastra la
     * penalización de sus propios errores durante el resto de la ventana.
     */
    public void clear(Long storeId, Flow flow, String email) {
        windows.invalidate(key(storeId, flow, email));
    }

    private static String key(Long storeId, Flow flow, String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return storeId + " " + flow.name() + " " + normalized;
    }
}
