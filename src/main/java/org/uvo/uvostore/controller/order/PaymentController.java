package org.uvo.uvostore.controller.order;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.order.CheckoutSessionResult;
import org.uvo.uvostore.service.order.PaymentService;
import org.uvo.uvostore.service.order.PaymentVerificationResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/create-checkout-session")
    public CheckoutSessionResult createCheckoutSession(@Valid @RequestBody CreateCheckoutSessionRequest request) {
        return paymentService.createCheckoutSession(request.orderId(), request.successUrl(), request.cancelUrl());
    }

    @PostMapping("/verify-payment")
    public PaymentVerificationResult verifyPayment(@Valid @RequestBody VerifyPaymentRequest request) {
        return paymentService.verifyPayment(request.sessionId());
    }

    @PostMapping("/stripe/webhook")
    public Map<String, Boolean> webhook(HttpServletRequest request) {
        try {
            String payload = new String(request.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String signature = request.getHeader("Stripe-Signature");
            paymentService.handleWebhook(payload, signature);
            return Map.of("received", true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
