package org.uvo.uvostore.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PROD-18. El id de cada petición: de dónde sale, dónde queda y que no se quede pegado al hilo. */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    @DisplayName("Sin cabecera genera un UUID y lo devuelve; dentro de la cadena está en el MDC, después ya no")
    void generatesAnIdAndCleansTheMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> inside = new AtomicReference<>();
        AtomicReference<String> headerInside = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            inside.set(MDC.get(RequestIdFilter.MDC_KEY));
            headerInside.set(((MockHttpServletResponse) res).getHeader(RequestIdFilter.HEADER));
        });

        String id = response.getHeader(RequestIdFilter.HEADER);
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(inside.get()).isEqualTo(id);
        // Puesta antes de la cadena: un filtro que escribe el error y confirma la respuesta ya no puede agregarla.
        assertThat(headerInside.get()).isEqualTo(id);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("Un id entrante con buen formato se respeta, para seguir la petición entre sistemas")
    void keepsAWellFormedIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader(RequestIdFilter.HEADER, "nginx-0123456789abcdef");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("nginx-0123456789abcdef");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "corto", "con espacio dentro", "linea\r\nFalsa: inyectada", "-empieza-con-guion",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    @DisplayName("Un id entrante mal formado se reemplaza (nunca se rechaza), y la cabecera de la petición queda intacta")
    void replacesAMalformedIncomingId(String incoming) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader(RequestIdFilter.HEADER, incoming);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestHeaderInside = new AtomicReference<>();

        filter.doFilter(request, response,
                (req, res) -> requestHeaderInside.set(((MockHttpServletRequest) req).getHeader(RequestIdFilter.HEADER)));

        String id = response.getHeader(RequestIdFilter.HEADER);
        assertThat(id).isNotEqualTo(incoming);
        assertThat(UUID.fromString(id)).isNotNull();
        // MercadoPagoController lee el x-request-id de la petición para verificar la firma de MercadoPago.
        assertThat(requestHeaderInside.get()).isEqualTo(incoming);
    }

    @Test
    @DisplayName("Si la cadena lanza, el MDC queda limpio igual: el hilo vuelve al pool sin el id de otra petición")
    void cleansTheMdcWhenTheChainThrows() {
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("se cayó");
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/"), new MockHttpServletResponse(),
                failing)).isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("Solo quita su clave del MDC: lo que otros pusieron sigue ahí")
    void removesOnlyItsOwnKey() throws Exception {
        MDC.put("otraClave", "valor");
        try {
            filter.doFilter(new MockHttpServletRequest("GET", "/"), new MockHttpServletResponse(), (req, res) -> { });
            assertThat(MDC.get("otraClave")).isEqualTo("valor");
        } finally {
            MDC.remove("otraClave");
        }
    }
}
