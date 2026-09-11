package org.uvo.uvostore.service.payment;

import org.uvo.uvostore.service.BusinessException;
import cl.transbank.model.MallTransactionCreateDetails;
import cl.transbank.webpay.webpayplus.WebpayPlus;
import cl.transbank.webpay.webpayplus.responses.WebpayPlusMallTransactionCommitResponse;
import cl.transbank.webpay.webpayplus.responses.WebpayPlusMallTransactionCreateResponse;
import cl.transbank.webpay.webpayplus.responses.WebpayPlusMallTransactionStatusResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.payment.PaymentGatewayConfig;
import org.uvo.uvostore.entity.payment.enums.PaymentGatewayType;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PaymentGatewayConfigRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.order.OrderStatusService;

import java.util.NoSuchElementException;

// Webpay Plus Mall: the parent commerce code + API key belong to the platform (application.
// properties webpay.*), issued once by Transbank; each store only owns its own CHILD commerce
// code, stored in that store's PaymentGatewayConfig. This is the real shape of a Transbank Mall
// integration — unlike Stripe, there's no per-store secret key to manage here.
@Service
public class WebpayServiceImpl implements WebpayService {

    private final OrderRepository orderRepository;
    private final PaymentGatewayConfigRepository configRepository;
    private final OrderStatusService orderStatusService;
    private final String parentCommerceCode;
    private final String apiKey;
    private final boolean production;
    private final String frontendUrl;

    public WebpayServiceImpl(
            OrderRepository orderRepository,
            PaymentGatewayConfigRepository configRepository,
            OrderStatusService orderStatusService,
            @Value("${webpay.parent-commerce-code}") String parentCommerceCode,
            @Value("${webpay.api-key}") String apiKey,
            @Value("${webpay.environment}") String environment,
            @Value("${app.frontend-url}") String frontendUrl) {
        this.orderRepository = orderRepository;
        this.configRepository = configRepository;
        this.orderStatusService = orderStatusService;
        this.parentCommerceCode = parentCommerceCode;
        this.apiKey = apiKey;
        this.production = "production".equalsIgnoreCase(environment);
        this.frontendUrl = frontendUrl;
    }

    @Override
    @Transactional
    public WebpayCreateResult createTransaction(Long orderId, String returnUrl) {
        Long storeId = TenantContext.requireStoreId();
        Order order = orderRepository.findById(orderId)
                .filter(o -> o.getStore().getId().equals(storeId))
                .orElseThrow(() -> new NoSuchElementException("Order " + orderId + " not found"));

        if (order.getPaymentStatus() != PaymentStatus.PENDING) {
            throw new BusinessException("Esta orden ya fue procesada");
        }

        String childCommerceCode = requireChildCommerceCode(storeId);

        // CLP has no minor unit — Transbank rejects amounts with decimals for Chilean-peso
        // commerce accounts (matches the rounding PaymentServiceImpl already does for Stripe).
        double amount = order.getTotal().setScale(0, java.math.RoundingMode.HALF_UP).doubleValue();
        MallTransactionCreateDetails details = MallTransactionCreateDetails.build(
                amount, childCommerceCode, order.getOrderNumber());

        WebpayPlusMallTransactionCreateResponse response;
        try {
            response = transaction().create(
                    order.getOrderNumber(),
                    order.getOrderNumber(),
                    returnUrl != null ? returnUrl : frontendUrl + "/checkout/webpay/return",
                    details);
        } catch (Exception e) {
            throw new IllegalStateException("Error al crear transacción Webpay: " + e.getMessage(), e);
        }

        order.setPaymentId(response.getToken());
        orderRepository.save(order);

        return new WebpayCreateResult(response.getToken(), response.getUrl());
    }

    @Override
    @Transactional
    public WebpayCommitResult commitTransaction(String token) {
        Long storeId = TenantContext.requireStoreId();
        Order order = orderRepository.findByPaymentId(token)
                .filter(o -> o.getStore().getId().equals(storeId))
                .orElseThrow(() -> new NoSuchElementException("Orden no encontrada para el token entregado"));

        WebpayPlusMallTransactionCommitResponse response;
        try {
            response = transaction().commit(token);
        } catch (Exception e) {
            throw new IllegalStateException("Error al confirmar transacción Webpay: " + e.getMessage(), e);
        }

        var detail = response.getDetails().get(0);
        boolean approved = detail.getResponseCode() == 0 && "AUTHORIZED".equals(detail.getStatus());

        if (approved) {
            if (order.getPaymentStatus() == PaymentStatus.PENDING) {
                // M4: the commit response carries what Transbank actually captured.
                order = orderStatusService.markPaid(order.getId(), token,
                        java.math.BigDecimal.valueOf(detail.getAmount()));
            }
        } else if (order.getPaymentStatus() == PaymentStatus.PENDING) {
            order = orderStatusService.markPaymentFailed(order.getId());
        }

        return new WebpayCommitResult(order.getId(), order.getOrderNumber(), detail.getStatus(), order.getPaymentStatus().name());
    }

    @Override
    @Transactional
    public boolean reconcile(Long orderId) {
        Long storeId = TenantContext.requireStoreId();
        Order order = orderRepository.findById(orderId)
                .filter(o -> o.getStore().getId().equals(storeId))
                .orElseThrow(() -> new NoSuchElementException("Order " + orderId + " not found"));

        if (order.getPaymentStatus() != PaymentStatus.PENDING) {
            return false;
        }
        // El token que createTransaction guardó. Sin él no hay nada que consultar.
        String token = order.getPaymentId();
        if (token == null || token.isBlank()) {
            return false;
        }

        WebpayPlusMallTransactionStatusResponse response;
        try {
            // status(), NUNCA commit(). Confirmar aquí una transacción que el cliente abandonó le
            // cobraría: la conciliación existe para averiguar qué pasó, no para hacer que pase.
            response = transaction().status(token);
        } catch (Exception e) {
            throw new IllegalStateException("Error al consultar estado de transacción Webpay: " + e.getMessage(), e);
        }

        if (response.getDetails() == null || response.getDetails().isEmpty()) {
            return false;
        }
        var detail = response.getDetails().get(0);
        if (detail.getResponseCode() != 0 || !"AUTHORIZED".equals(detail.getStatus())) {
            return false;
        }

        // markPaid ya es idempotente y ya verifica el monto (M4): si el webhook de vuelta llega
        // mientras corre la conciliación, no se cobra ni se marca dos veces.
        orderStatusService.markPaid(order.getId(), token, java.math.BigDecimal.valueOf(detail.getAmount()));
        return orderRepository.findById(orderId).orElseThrow().getPaymentStatus() == PaymentStatus.PAID;
    }

    private String requireChildCommerceCode(Long storeId) {
        PaymentGatewayConfig config = configRepository.findByStoreIdAndGateway(storeId, PaymentGatewayType.WEBPAY)
                .filter(PaymentGatewayConfig::isEnabled)
                .orElseThrow(() -> new BusinessException("Webpay no está habilitado para esta tienda"));
        String childCommerceCode = config.getCredentials().get("childCommerceCode");
        if (childCommerceCode == null || childCommerceCode.isBlank()) {
            throw new BusinessException("Falta configurar el código de comercio (childCommerceCode) de Webpay para esta tienda");
        }
        return childCommerceCode;
    }

    // protected, y no private, para poder sustituirla en test: es el único punto por el que esta
    // clase habla con Transbank, y lo que hay que poder comprobar sin credenciales reales es
    // justamente qué método de la pasarela se llama — reconcile() debe usar status() y nunca
    // commit(). Ver WebpayReconcileTest.
    protected WebpayPlus.MallTransaction transaction() {
        return production
                ? WebpayPlus.MallTransaction.buildForProduction(parentCommerceCode, apiKey)
                : WebpayPlus.MallTransaction.buildForIntegration(parentCommerceCode, apiKey);
    }
}
