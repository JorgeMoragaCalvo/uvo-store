package org.uvo.uvostore.service.shipping.carrier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.uvo.uvostore.entity.shipping.ShippingMethod;
import org.uvo.uvostore.entity.shipping.enums.ShippingCarrier;
import org.uvo.uvostore.service.shipping.ShippingOption;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

// UNVERIFIED CONTRACT (Fase 3, built without live sandbox credentials — see conversation with the
// user before implementing): shaped against Chilexpress's publicly documented Cotizador/Rating API
// (developers.wschilexpress.com, "GET /rating/api/v1.0/rate/courier"), which as of this writing
// expects an Ocp-Apim-Subscription-Key header and originCountyCode/destinationCountyCode query
// params identifying communes by Chilexpress's own alphanumeric county codes (e.g. "STGO"), NOT
// free-text commune names. This app only stores free-text region/commune strings (from Chilean
// government administrative names), so ShippingCarrierQuoteRequest's originCommune/
// destinationCommune are passed through as-is; they will only resolve correctly if the store's
// checkout communes happen to already be Chilexpress county codes. A real commune-name -> county-
// code lookup table is required before this can be trusted in production — until then, prefer
// configuring `originCountyCode` explicitly in the method's credentials (which this client does
// use, overriding the request's originCommune) and treat destination resolution as the known gap.
// Verify every field name/response shape against current Chilexpress docs before enabling.
@Component
public class ChilexpressQuoteClient implements ShippingCarrierQuoteClient {

    private static final Logger log = LoggerFactory.getLogger(ChilexpressQuoteClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final String baseUrl;

    public ChilexpressQuoteClient(@Value("${chilexpress.quote-url:https://testservices.wschilexpress.com/rating/api/v1.0/rate/courier}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.restClient = RestClient.builder()
                .requestFactory(clientRequestFactory())
                .build();
    }

    @Override
    public ShippingCarrier carrier() {
        return ShippingCarrier.CHILEXPRESS;
    }

    @Override
    public Optional<ShippingOption> quote(ShippingMethod method, ShippingCarrierQuoteRequest request) {
        Map<String, String> credentials = method.getApiCredentials();
        String subscriptionKey = credentials.get("subscriptionKey");
        if (subscriptionKey == null || subscriptionKey.isBlank()) {
            log.debug("Chilexpress no configurado para el método {} (falta subscriptionKey)", method.getId());
            return Optional.empty();
        }
        String originCode = credentials.getOrDefault("originCountyCode", request.originCommune());
        String destinationCode = request.destinationCommune();
        if (originCode == null || originCode.isBlank() || destinationCode == null || destinationCode.isBlank()) {
            return Optional.empty();
        }

        BigDecimal weight = request.totalWeightKg() != null && request.totalWeightKg().signum() > 0
                ? request.totalWeightKg() : BigDecimal.ONE;
        BigDecimal declaredValue = request.declaredValue() != null ? request.declaredValue() : BigDecimal.ZERO;

        try {
            String uri = baseUrl
                    + "?originCountyCode=" + originCode
                    + "&destinationCountyCode=" + destinationCode
                    + "&package.weight=" + weight.setScale(2, RoundingMode.UP)
                    + "&package.height=10&package.width=10&package.length=10"
                    + "&declaredWorth=" + declaredValue.setScale(0, RoundingMode.UP)
                    + "&productType=3&contentType=1";

            String response = restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .header("Ocp-Apim-Subscription-Key", subscriptionKey)
                    .retrieve()
                    .body(String.class);

            return parseCheapestService(response, method);
        } catch (Exception e) {
            log.warn("Error consultando cotizador Chilexpress para método {}: {}", method.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ShippingOption> parseCheapestService(String responseBody, ShippingMethod method) {
        if (responseBody == null) return Optional.empty();
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode services = root.path("data");
            if (!services.isArray() || services.isEmpty()) return Optional.empty();

            BigDecimal cheapest = null;
            for (JsonNode service : services) {
                JsonNode valueNode = service.path("serviceValue");
                if (!valueNode.isNumber()) continue;
                BigDecimal value = valueNode.decimalValue();
                if (cheapest == null || value.compareTo(cheapest) < 0) {
                    cheapest = value;
                }
            }
            if (cheapest == null) return Optional.empty();

            return Optional.of(new ShippingOption(
                    method.getId(), method.getName(), cheapest, deliveryTimeString(method), cheapest.signum() == 0));
        } catch (Exception e) {
            log.warn("Respuesta inesperada del cotizador Chilexpress: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String deliveryTimeString(ShippingMethod method) {
        if (method.getMinDeliveryDays() != null && method.getMaxDeliveryDays() != null) {
            return method.getMinDeliveryDays() + "-" + method.getMaxDeliveryDays() + " días hábiles";
        }
        return "A coordinar";
    }

    private static org.springframework.http.client.ClientHttpRequestFactory clientRequestFactory() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        return factory;
    }
}
