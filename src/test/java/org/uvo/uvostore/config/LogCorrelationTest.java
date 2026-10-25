package org.uvo.uvostore.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.security.PosApiKeyAuthFilter;
import org.uvo.uvostore.security.PosWebhookAuthFilter;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.security.TenantResolutionFilter;
import org.uvo.uvostore.support.IntegrationTestSupport;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROD-15b. La tienda en cada línea de log: quién la pone en el MDC, quién la quita, y cómo se ve.
 *
 * <p>Los filtros se llaman directamente, con una cadena que mira el MDC mientras la petición está dentro: lo que
 * importa es qué ve el código que corre durante la petición y qué queda en el hilo después.
 */
class LogCorrelationTest extends IntegrationTestSupport {

    @Value("${logging.pattern.correlation}")
    private String correlationPattern;

    @Autowired
    private TenantResolutionFilter tenantResolutionFilter;
    @Autowired
    private PosApiKeyAuthFilter posApiKeyAuthFilter;
    @Autowired
    private PosWebhookAuthFilter posWebhookAuthFilter;
    @Autowired
    private PosConnectionRepository connectionRepository;

    @AfterEach
    void cleanMdc() {
        MDC.clear();
        TenantContext.clear();
    }

    @Test
    @DisplayName("El patrón muestra la tienda junto al id, y sin tienda no deja un 'store=' colgando")
    void thePatternShowsTheStoreOnlyWhenThereIsOne() throws Exception {
        PatternLayout layout = productionLayout();

        assertThat(render(layout, Map.of(LogContext.REQUEST_ID, "it-req-15b-0001", LogContext.STORE_ID, "42")))
                .contains("[it-req-15b-0001 store=42] o.u.u.security.PosApiKeyAuthFilter - mensaje");
        assertThat(render(layout, Map.of(LogContext.REQUEST_ID, "it-req-15b-0002")))
                .contains("[it-req-15b-0002] o.u.u.security.PosApiKeyAuthFilter - mensaje")
                .doesNotContain("store=");
        assertThat(render(layout, Map.of(LogContext.REQUEST_ID, "job-0123456789ab", LogContext.STORE_ID, "7")))
                .contains("[job-0123456789ab store=7] ");
        assertThat(render(layout, Map.of()))
                .contains("[] o.u.u.security.PosApiKeyAuthFilter - mensaje")
                .doesNotContain("%replace")
                .doesNotContain("%logger");
    }

    @Test
    @DisplayName("Una petición del storefront lleva su tienda en el MDC durante la cadena, y la suelta al salir")
    void aStorefrontRequestCarriesItsStore() throws Exception {
        Store store = createStore("log-correlation");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
        request.addHeader("Host", hostHeader(store));
        AtomicReference<String> seen = new AtomicReference<>();

        tenantResolutionFilter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(MDC.get(LogContext.STORE_ID)));

        assertThat(seen.get()).isEqualTo(store.getId().toString());
        assertThat(MDC.get(LogContext.STORE_ID)).isNull();
    }

    @Test
    @DisplayName("Un Host que no resuelve una tienda no pone nada")
    void anUnknownHostPutsNothing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.addHeader("Host", "no-existe-" + nextSeq() + ".localhost");
        AtomicReference<String> seen = new AtomicReference<>("sin mirar");

        tenantResolutionFilter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(MDC.get(LogContext.STORE_ID)));

        assertThat(seen.get()).isNull();
    }

    @Test
    @DisplayName("runWithin pone la tienda del trabajo y al salir devuelve la que había")
    void runWithinRestoresThePreviousStore() {
        Store outer = createStore("log-outer");
        Store inner = createStore("log-inner");
        AtomicReference<String> seen = new AtomicReference<>();

        TenantContext.set(outer);
        TenantContext.runWithin(inner, () -> seen.set(MDC.get(LogContext.STORE_ID)));

        assertThat(seen.get()).isEqualTo(inner.getId().toString());
        assertThat(MDC.get(LogContext.STORE_ID)).isEqualTo(outer.getId().toString());

        TenantContext.clear();
        assertThat(MDC.get(LogContext.STORE_ID)).isNull();

        // Desde un hilo de pool (sin tienda previa), al salir no queda nada.
        TenantContext.runWithin(inner, () -> { });
        assertThat(MDC.get(LogContext.STORE_ID)).isNull();
    }

    @Test
    @DisplayName("La sincronización del POS lleva la tienda de la conexión, que no sale del Host")
    void aPosSyncRequestCarriesTheStoreOfItsConnection() throws Exception {
        Store store = createStore("log-pos-sync");
        PosConnection connection = connection(store);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/sync/product");
        request.addHeader("Authorization", "Bearer " + connection.getApiKey());
        request.addHeader("X-Company-ID", connection.getCompanyId().toString());
        AtomicReference<String> seen = new AtomicReference<>();

        posApiKeyAuthFilter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(MDC.get(LogContext.STORE_ID)));

        assertThat(seen.get()).isEqualTo(store.getId().toString());
        assertThat(MDC.get(LogContext.STORE_ID)).isNull();
    }

    @Test
    @DisplayName("Un webhook del POS también lleva la tienda de su conexión")
    void aPosWebhookCarriesTheStoreOfItsConnection() throws Exception {
        Store store = createStore("log-pos-webhook");
        PosConnection connection = connection(store);
        String body = "{\"event\":\"stock.updated\",\"n\":" + nextSeq() + "}";
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhooks/pos/stock-updated");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.addHeader("X-Signature", hmac(body + timestamp, connection.getWebhookSecret()));
        request.addHeader("X-Company-ID", connection.getCompanyId().toString());
        request.addHeader("X-Timestamp", timestamp);
        AtomicReference<String> seen = new AtomicReference<>();

        posWebhookAuthFilter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(MDC.get(LogContext.STORE_ID)));

        assertThat(seen.get()).isEqualTo(store.getId().toString());
        assertThat(MDC.get(LogContext.STORE_ID)).isNull();
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private PosConnection connection(Store store) {
        long seq = nextSeq();
        PosConnection connection = new PosConnection();
        connection.setStore(store);
        connection.setCompanyName("Empresa log " + seq);
        connection.setCompanyId(900_000_000L + seq);
        connection.setApiUrl("https://pos.que-no-sale.local/api");
        connection.setApiKey("clave-log-" + seq);
        connection.setWebhookSecret("secreto-log-" + seq);
        connection.setActive(true);
        return connectionRepository.save(connection);
    }

    private static String hmac(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    /** El patrón de producción (logback-spring.xml, perfil !default) con la propiedad de verdad dentro. */
    private PatternLayout productionLayout() throws Exception {
        String xml = new String(new ClassPathResource("logback-spring.xml").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("name=\"!default\">.*?<pattern>(.*?)</pattern>", Pattern.DOTALL).matcher(xml);
        assertThat(matcher.find()).isTrue();
        LoggerContext context = new LoggerContext();
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern(matcher.group(1).replace("${LOG_CORRELATION_PATTERN:-}", correlationPattern));
        layout.start();
        return layout;
    }

    private static String render(PatternLayout layout, Map<String, String> mdc) {
        LoggingEvent event = new LoggingEvent(LogCorrelationTest.class.getName(),
                ((LoggerContext) layout.getContext()).getLogger("org.uvo.uvostore.security.PosApiKeyAuthFilter"),
                Level.INFO, "mensaje", null, null);
        event.setMDCPropertyMap(new HashMap<>(mdc));
        return layout.doLayout(event);
    }
}
