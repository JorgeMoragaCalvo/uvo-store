package org.uvo.uvostore.controller.advice;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.order.OutOfStockException;
import org.uvo.uvostore.service.order.ShippingUnavailableException;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> handleNotFound(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage()));
    }

    // Un archivo estático que no existe —una imagen de /uploads/** borrada o con la ruta mal— caía
    // en el handler genérico: respondía 500 y, peor, mandaba un evento a Sentry. Una galería rota
    // se convertía así en una tormenta de alertas por algo que no es un fallo del servidor. Mismo
    // criterio que M1: lo que no existe no se cuenta como caída.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleMissingStaticResource(NoResourceFoundException ex) {
        log.debug("Recurso estático no encontrado: {}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(HttpStatus.NOT_FOUND.value(), "Not Found", "El recurso solicitado no existe."));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of(HttpStatus.UNAUTHORIZED.value(), "Unauthorized", ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(HttpStatus.FORBIDDEN.value(), "Forbidden", ex.getMessage()));
    }

    // M1: IllegalStateException used to be mapped here too, which meant a wrapped Stripe error or a
    // failed decryption reached the customer as a 400 carrying the raw message — and never reached
    // Sentry, because only the generic handler captures. The ~19 business cases now throw
    // BusinessException; whatever still throws IllegalStateException is a bug, and falls through to
    // the 500 handler where it belongs.
    //
    // IllegalArgumentException stays: every use of it in this codebase is genuine input validation
    // (UploadedImageValidator, the gateway config checks) and none of them wraps a cause.
    @ExceptionHandler({BusinessException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiError> handleBadRequest(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage()));
    }

    // These four are the client's mistake, not the server's, and every one of them was reaching the
    // generic handler and coming back as a 500: a malformed JSON body, a missing or badly typed
    // query parameter, and a sort field that doesn't name a real property (the second net under
    // M8's allowlist). Messages aren't echoed — they quote internals like Jackson paths and entity
    // names.
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            PropertyReferenceException.class
    })
    public ResponseEntity<ApiError> handleMalformedRequest(Exception ex) {
        log.warn("Petición mal formada: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), "Bad Request",
                        "La solicitud no es válida. Revisa los datos enviados."));
    }

    // 409, not 400: nothing is wrong with the request — the stock ran out. The SPA needs to tell
    // the two apart to show "alguien lo compró antes" instead of a validation error.
    @ExceptionHandler(OutOfStockException.class)
    public ResponseEntity<ApiError> handleOutOfStock(OutOfStockException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(HttpStatus.CONFLICT.value(), "Conflict", ex.getMessage(), ex.getErrors()));
    }

    // Same reasoning as out-of-stock: the request is fine, the store just can't fulfil it.
    @ExceptionHandler(ShippingUnavailableException.class)
    public ResponseEntity<ApiError> handleShippingUnavailable(ShippingUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(HttpStatus.CONFLICT.value(), "Conflict", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.put(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), "Validation Failed", "Uno o más campos son inválidos", fieldErrors));
    }

    // Catches only what the handlers above don't — genuinely unexpected exceptions, not the
    // expected 4xx business cases already handled specifically. Reported to Sentry (a no-op if
    // SENTRY_DSN isn't configured, see UvoStoreApplication.main()) before returning a generic 500,
    // so real bugs surface without leaking internals to the client.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        Sentry.captureException(ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error", "Ocurrió un error inesperado"));
    }
}
