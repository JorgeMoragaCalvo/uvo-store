package org.uvo.uvostore.service;

import java.time.Duration;

/**
 * F18. Un límite alcanzado, desde código de aplicación y no desde el filtro.
 *
 * <p>{@code RateLimitFilter} escribe su 429 a mano porque es un filtro y no pasa por
 * {@code GlobalExceptionHandler}. Lo que se lanza aquí termina en el mismo cuerpo y con la misma cabecera
 * {@code Retry-After} —ver el handler— para que el cliente no tenga que distinguir de dónde salió.
 */
public class TooManyRequestsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyRequestsException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
