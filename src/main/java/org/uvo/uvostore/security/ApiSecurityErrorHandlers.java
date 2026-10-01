package org.uvo.uvostore.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.controller.advice.ApiError;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * F19. Las dos respuestas de la cadena de seguridad, que antes no estaban declaradas.
 *
 * <p>Sin {@code exceptionHandling(...)} en {@code SecurityConfig} —y con {@code httpBasic} y
 * {@code formLogin} desactivados— Spring Security caía a su respuesta por defecto y una petición
 * <b>sin autenticar</b> a una ruta protegida respondía <b>403</b>. El panel guarda el token en
 * {@code localStorage} y su interceptor solo cierra sesión ante 401, así que un token caducado o
 * revocado dejaba la SPA con un token muerto en la mano: el guarda de ruta no se disparaba —seguía
 * habiendo token— y el administrador veía errores en todas las pantallas sin volver nunca al login.
 *
 * <p><b>La distinción no se programa aquí, ya existe</b>: {@code ExceptionTranslationFilter} llama al
 * punto de entrada cuando la autenticación es anónima y al manejador de acceso denegado cuando hay un
 * autenticado sin autoridad suficiente. Lo único que faltaba era declarar los dos.
 *
 * <p>Y los dos escriben el cuerpo <b>directamente</b>, no con {@code sendError}: eso despacha a
 * {@code /error} y devuelve el JSON por defecto de Boot, que no trae el campo {@code message} que lee el
 * interceptor. Por eso lo que se veía en el panel no era un mensaje del proyecto sino el texto de axios
 * ("Request failed with status code 403").
 *
 * <p>Ojo con el tercer caso, que no pasa por aquí: un administrador autenticado al que le falta un
 * permiso recibe su 403 de {@code @PreAuthorize} a través de {@code GlobalExceptionHandler}, y así tiene
 * que seguir. Es la razón por la que la SPA <b>no</b> puede cerrar sesión ante un 403: echaría al login a
 * quien simplemente abrió una sección que no le toca.
 */
@Component
public class ApiSecurityErrorHandlers {

    // El mismo mapper que serializa el resto de las respuestas (Jackson 3, el de Boot 4), para que el
    // ApiError de aquí salga con la forma exacta del que devuelve GlobalExceptionHandler. Ojo: en este
    // proyecto conviven Jackson 2 y 3 — los `new ObjectMapper()` sueltos de MercadoPagoServiceImpl y
    // compañía son el de com.fasterxml, que NO es el que está en el contexto.
    private final ObjectMapper objectMapper;

    public ApiSecurityErrorHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Falta la credencial, o no vale: 401, y que el cliente vuelva a autenticarse. */
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            // Lo que exige el estándar para un 401. "Bearer" no dispara el diálogo del navegador que sí
            // provocaría "Basic".
            response.setHeader("WWW-Authenticate", "Bearer");
            // Un único mensaje a propósito: no distingue ausente de caducado de revocado de "de otra
            // tienda". Al cliente le sirve lo mismo —volver a entrar— y el estado de revocación de una
            // cuenta no tiene por qué viajar en una respuesta pública.
            write(response, HttpStatus.UNAUTHORIZED, "Unauthorized",
                    "No autenticado. Inicia sesión para continuar.");
        };
    }

    /** Hay un autenticado, pero no con lo que esta ruta pide: 403, y la sesión no se toca. */
    public AccessDeniedHandler accessDeniedHandler() {
        return (HttpServletRequest request, HttpServletResponse response, org.springframework.security.access.AccessDeniedException ex) ->
                write(response, HttpStatus.FORBIDDEN, "Forbidden", "No tienes permiso para esta acción.");
    }

    private void write(HttpServletResponse response, HttpStatus status, String error, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiError.of(status.value(), error, message));
    }
}
