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

    // PROD-04. Ni la URL de notificación ni las de retorno se deciden aquí: las resuelve el servicio desde
    // la tienda de la orden. Antes la primera se armaba con el scheme/host/puerto de esta petición y las
    // otras tres llegaban en el cuerpo sin validar.
    @PostMapping("/create-preference")
    public MercadoPagoPreferenceResult createPreference(@Valid @RequestBody MercadoPagoCreateRequest request) {
        return mercadoPagoService.createPreference(request.orderId());
    }

    // MercadoPago POSTs a small JSON body here whenever a payment's status changes — must stay on
    // the store's own subdomain (same as the /create-preference call that registered this URL) so
    // TenantResolutionFilter can resolve which store's credentials to verify the payment with.
    @PostMapping("/webhook")
    public Map<String, Boolean> webhook(HttpServletRequest request) {
        try {
            String payload = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            // M3: the signature headers were being read off the wire and thrown away. The service
            // verifies them before it queries MercadoPago's API.
            mercadoPagoService.handleWebhook(payload,
                    request.getHeader("x-signature"), request.getHeader("x-request-id"));
            return Map.of("received", true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
