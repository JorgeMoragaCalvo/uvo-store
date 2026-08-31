package org.uvo.uvostore.service.order;

import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.payment.EncryptionKeyHolder;
import org.uvo.uvostore.entity.settings.Setting;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.SettingRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.settings.SecretCrypto;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Unit test (no Spring context, no real Stripe call) for the C1/C2 fixes: PaymentServiceImpl no
// longer mutates the shared static Stripe.apiKey, and the Checkout Session it builds always
// charges exactly order.getTotal() regardless of discounts/tax-inclusive pricing.
class PaymentServiceImplTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final SettingRepository settingRepository = mock(SettingRepository.class);
    private final OrderStatusService orderStatusService = mock(OrderStatusService.class);

    private PaymentServiceImpl paymentService;

    @BeforeEach
    void setUp() {
        // EncryptionKeyHolder is normally Spring-constructed, but its constructor's only real job
        // is setting a static field — safe to construct directly here so SecretCrypto (used by
        // requestOptions()) has a key to work with, without needing a full Spring context.
        new EncryptionKeyHolder("d/S6b0SJRrMoJhWogobxo7fJzLyhQox5Nq709OLTiNI=");

        paymentService = new PaymentServiceImpl(
                orderRepository, settingRepository, orderStatusService,
                "sk_test_fallback", "whsec_fallback", "clp", "http://localhost:5173");

        Store store = Store.builder().id(1L).name("Tienda de prueba").slug("test").status("active").build();
        TenantContext.set(store);
        when(settingRepository.findByStoreIdAndSettingKey(1L, "currency")).thenReturn(java.util.Optional.empty());
        when(settingRepository.findByStoreIdAndSettingKey(1L, "stripe_secret_key")).thenReturn(java.util.Optional.empty());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Order orderWith(BigDecimal subtotal, BigDecimal discountAmount, BigDecimal taxAmount, BigDecimal total) {
        OrderItem item = OrderItem.builder()
                .productName("Producto de prueba")
                .productSku("SKU-1")
                .quantity(2)
                .price(subtotal.divide(BigDecimal.valueOf(2)))
                .subtotal(subtotal)
                .build();

        return Order.builder()
                .id(1L)
                .orderNumber("ORD-TEST-1")
                .customerEmail("cliente@test.local")
                .customerFirstName("Ana")
                .customerLastName("Pérez")
                .subtotal(subtotal)
                .discountAmount(discountAmount)
                .shippingCost(BigDecimal.ZERO)
                .taxAmount(taxAmount)
                .total(total)
                .items(List.of(item))
                .build();
    }

    @Test
    void chargesExactlyTheOrderTotalWithATaxInclusivePriceAndACoupon() {
        // subtotal 48970 (already tax-inclusive), 10% coupon (-4115.13), tax portion 7818.74 —
        // mirrors the real cart-calculate scenario that exposed C2 (double tax + ignored discount).
        Order order = orderWith(
                new BigDecimal("48970.00"),
                new BigDecimal("4115.13"),
                new BigDecimal("7818.74"),
                new BigDecimal("44854.87"));

        SessionCreateParams params = paymentService.buildSessionParams(order, null, null);

        assertEquals(1, params.getLineItems().size(), "should be a single line item, not items+shipping+tax");
        SessionCreateParams.LineItem lineItem = params.getLineItems().get(0);
        long chargedAmount = lineItem.getPriceData().getUnitAmount() * lineItem.getQuantity();
        long expectedTotal = order.getTotal().setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();

        assertEquals(expectedTotal, chargedAmount, "Stripe must be charged exactly order.getTotal(), discount included and tax not duplicated");
    }

    @Test
    void chargesExactlyTheOrderTotalWithNoDiscountAndTaxExclusivePricing() {
        Order order = orderWith(new BigDecimal("10000.00"), BigDecimal.ZERO, new BigDecimal("1900.00"), new BigDecimal("11900.00"));

        SessionCreateParams params = paymentService.buildSessionParams(order, null, null);

        long chargedAmount = params.getLineItems().get(0).getPriceData().getUnitAmount();
        assertEquals(11900L, chargedAmount);
    }

    @Test
    void neverAssignsTheSharedStaticStripeApiKey() {
        // Regression guard for C1: creating session params must not touch the SDK's global
        // mutable Stripe.apiKey field — the key is only ever read locally into a per-call
        // RequestOptions (see PaymentServiceImpl.requestOptions()), never assigned globally.
        com.stripe.Stripe.apiKey = null;

        Order order = orderWith(new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1000.00"));
        paymentService.buildSessionParams(order, null, null);

        assertEquals(null, com.stripe.Stripe.apiKey);
    }

    @Test
    void decryptsTheStripeSecretKeyStoredByAdminSettings() {
        // C3 fix — SettingsServiceImpl.setSecret() now stores stripe_secret_key encrypted (see
        // SecretCrypto), so requestOptions() must decrypt it before handing it to the Stripe SDK.
        String realKey = "sk_test_real_key_from_admin_settings";
        Setting encrypted = new Setting();
        encrypted.setValue(SecretCrypto.encrypt(realKey));
        when(settingRepository.findByStoreIdAndSettingKey(1L, "stripe_secret_key")).thenReturn(java.util.Optional.of(encrypted));

        RequestOptions options = paymentService.requestOptions();

        assertEquals(realKey, options.getApiKey());
    }

    @Test
    void fallsBackToTheUndecryptedConfiguredKeyWhenNoSettingIsStored() {
        // The application.properties fallback was never encrypted, so it must be used as-is.
        RequestOptions options = paymentService.requestOptions();

        assertEquals("sk_test_fallback", options.getApiKey());
    }
}
