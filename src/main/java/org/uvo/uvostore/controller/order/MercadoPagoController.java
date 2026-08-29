package org.uvo.uvostore.controller.order;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.payment.MercadoPagoPreferenceResult;
import org.uvo.uvostore.service.payment.MercadoPagoService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@io.swagger.v3.oas.annotations.tags.Tag(name = "MercadoPago (público)", description = "Creación de preferencias de pago con MercadoPago")
@RestController
@RequestMapping("/api/v1/mercadopago")
public class MercadoPagoController {

    private final MercadoPagoService mercadoPagoService;

    public MercadoPagoController(MercadoPagoService mercadoPagoService) {
        this.mercadoPagoService = mercadoPagoService;
    }

    @PostMapping("/create-preference")
    public MercadoPagoPreferenceResult createPreference(@Valid @RequestBody MercadoPagoCreateRequest request, HttpServletRequest httpRequest) {
        String notificationUrl = defaultWebhookUrl(httpRequest);
        return mercadoPagoService.createPreference(
                request.orderId(), request.successUrl(), request.failureUrl(), request.pendingUrl(), notificationUrl);
    }

    // MercadoPago POSTs a small JSON body here whenever a payment's status changes — must stay on
    // the store's own subdomain (same as the /create-preference call that registered this URL) so
    // TenantResolutionFilter can resolve which store's credentials to verify the payment with.
    @PostMapping("/webhook")
    public Map<String, Boolean> webhook(HttpServletRequest request) {
        try {
            String payload = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            mercadoPagoService.handleWebhook(payload);
            return Map.of("received", true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String defaultWebhookUrl(HttpServletRequest request) {
        return request.getScheme() + "://" + request.getServerName()
                + (request.getServerPort() == 80 || request.getServerPort() == 443 ? "" : ":" + request.getServerPort())
                + "/api/v1/mercadopago/webhook";
    }
}
