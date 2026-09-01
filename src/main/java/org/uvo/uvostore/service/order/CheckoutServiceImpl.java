package org.uvo.uvostore.service.order;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.common.Address;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.OrderStatusHistory;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.payment.PaymentGatewayConfig;
import org.uvo.uvostore.entity.payment.enums.PaymentGatewayType;
import org.uvo.uvostore.entity.settings.Setting;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PaymentGatewayConfigRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;
import org.uvo.uvostore.repository.SettingRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.customer.CustomerService;
import org.uvo.uvostore.service.order.event.OrderCompletedEvent;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

// Consolidates the checkout order-creation path onto the single CartPricingService calculator
// (see CartPricingServiceImpl) instead of CheckoutController::store()'s own hardcoded
// subtotal/shipping/tax math — that duplication (and its client-trusted item price) is exactly
// what services.md's "three money calculators" note flags as a bug, not a pattern to replicate.
@Service
public class CheckoutServiceImpl implements CheckoutService {

    private static final String SKU_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final CartPricingService cartPricingService;
    private final CartService cartService;
    private final CouponService couponService;
    private final CustomerService customerService;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProductVariationRepository variationRepository;
    private final SettingRepository settingRepository;
    private final PaymentGatewayConfigRepository paymentGatewayConfigRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    public CheckoutServiceImpl(
            CartPricingService cartPricingService,
            CartService cartService,
            CouponService couponService,
            CustomerService customerService,
            OrderRepository orderRepository,
            ProductRepository productRepository,
            ProductVariationRepository variationRepository,
            SettingRepository settingRepository,
            PaymentGatewayConfigRepository paymentGatewayConfigRepository,
            ApplicationEventPublisher applicationEventPublisher) {
        this.cartPricingService = cartPricingService;
        this.cartService = cartService;
        this.couponService = couponService;
        this.customerService = customerService;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.variationRepository = variationRepository;
        this.settingRepository = settingRepository;
        this.paymentGatewayConfigRepository = paymentGatewayConfigRepository;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    @Transactional
    public OrderConfirmation checkout(CheckoutCommand command) {
        if (command.lines() == null || command.lines().isEmpty()) {
            throw new IllegalArgumentException("El carrito no puede estar vacío");
        }

        // C5: the checkout used to create the order without looking at stock at all — the only
        // check lived in StockDecrementListener, AFTER the customer had paid. Reuses the cart's own
        // validation (CartServiceImpl.validateItems) rather than duplicating it: same manageStock
        // handling, same Spanish messages, same per-item error keys the SPA already renders.
        // This closes the common case; the conditional UPDATE at payment time still covers the
        // narrow window between here and the gateway's confirmation.
        CartValidationResult stockCheck = cartService.validateItems(toCartItems(command.lines()));
        if (!stockCheck.valid()) {
            // Resolve the lines first: a product belonging to another store must still answer 404
            // (tenant isolation), never a 409 that would confirm it exists somewhere else.
            command.lines().forEach(this::resolveLineOrThrow);
            throw new OutOfStockException(stockCheck.errors());
        }

        CartTotals totals = cartPricingService.price(command.lines(), command.couponCode(), command.region(), command.commune());

        Customer customer = customerService.findOrCreateGuest(
                command.customerEmail(), command.customerFirstName(), command.customerLastName(), command.customerPhone());
        customer = customerService.markInvitedIfGuest(customer);

        Order order = new Order();
        order.setStore(TenantContext.requireCurrent());
        order.setOrderNumber("ORD-" + randomOrderSuffix());
        order.setCustomer(customer);
        order.setCustomerEmail(command.customerEmail());
        order.setCustomerFirstName(command.customerFirstName());
        order.setCustomerLastName(command.customerLastName());
        order.setCustomerPhone(command.customerPhone());

        order.setSubtotal(totals.subtotalWithoutTax());
        order.setDiscountAmount(totals.discountAmount());
        order.setShippingCost(totals.shippingCost());
        order.setTaxAmount(totals.taxAmount());
        order.setTotal(totals.total());

        order.setStatus(OrderStatus.PENDING);
        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);
        order.setPaymentMethod(org.uvo.uvostore.entity.order.enums.PaymentMethodType.valueOf(command.paymentMethod().toUpperCase()));
        order.setCustomerNotes(command.customerNotes());
        order.setPosSynced(false);
        order.setSyncAttempts(0);

        Address address = buildAddress(command);
        order.setShippingAddress(address);
        order.setBillingAddress(address);
        order.setShippingRegion(command.region());
        order.setShippingCommune(command.commune());
        order.setShippingPostalCode(command.shippingAddress().postalCode());

        if (command.couponCode() != null && !command.couponCode().isBlank()) {
            CouponValidationResult couponResult = couponService.validate(command.couponCode(), totals.subtotalWithoutTax(), customer.getId());
            if (couponResult.valid()) {
                // Claimed here, before the order is built, because the discount is already baked
                // into `totals` above. If the coupon ran out in the meantime we can't quietly
                // re-price without it — the customer would be charged an amount they never saw —
                // so the checkout fails and they can retry. The claim is part of this transaction,
                // so any later failure rolls it back.
                if (!couponService.claimUsage(couponResult.coupon())) {
                    throw new IllegalStateException("El cupón alcanzó su límite de usos.");
                }
                order.setCoupon(couponResult.coupon());
                order.setCouponCode(command.couponCode());
            }
        }

        List<OrderItem> items = new ArrayList<>();
        for (CartLineCommand line : command.lines()) {
            items.add(buildOrderItem(order, line));
        }
        order.setItems(items);

        OrderStatusHistory pending = new OrderStatusHistory();
        pending.setOrder(order);
        pending.setStatus(OrderStatus.PENDING.name());
        pending.setNotes("Orden creada");
        order.getStatusHistory().add(pending);

        Order saved = orderRepository.save(order);

        if (order.getCoupon() != null) {
            couponService.recordUsage(order.getCoupon(), saved, customer);
        }

        // Ports event(new OrderCompleted($order)) — PosNotificationListener reacts to this
        // AFTER_COMMIT.
        applicationEventPublisher.publishEvent(new OrderCompletedEvent(saved.getId()));

        return new OrderConfirmation(saved.getId(), saved.getOrderNumber(), saved.getTotal());
    }

    @Override
    @Transactional(readOnly = true)
    public CheckoutConfigDto getConfig() {
        Long storeId = TenantContext.requireStoreId();
        return new CheckoutConfigDto(
                stringSetting("stripe_public_key", ""),
                boolSetting("stripe_enabled", false),
                paymentGatewayConfigRepository.findByStoreIdAndGateway(storeId, PaymentGatewayType.WEBPAY)
                        .map(PaymentGatewayConfig::isEnabled).orElse(false),
                paymentGatewayConfigRepository.findByStoreIdAndGateway(storeId, PaymentGatewayType.MERCADOPAGO)
                        .map(PaymentGatewayConfig::isEnabled).orElse(false),
                boolSetting("shipping_enabled", true),
                decimalSetting("default_shipping_cost", BigDecimal.ZERO),
                boolSetting("free_shipping_enabled", false),
                decimalSetting("free_shipping_threshold", BigDecimal.ZERO),
                boolSetting("allow_guest_checkout", true),
                boolSetting("require_phone", false),
                decimalSetting("tax_rate", BigDecimal.valueOf(19)),
                stringSetting("currency", "CLP"),
                stringSetting("currency_symbol", "$")
        );
    }

    // Same tenant-scoped lookup buildOrderItem does, without building anything — used to keep the
    // 404-before-409 ordering above.
    private void resolveLineOrThrow(CartLineCommand line) {
        Long storeId = TenantContext.requireStoreId();
        if (line.variationId() != null) {
            variationRepository.findById(line.variationId())
                    .filter(v -> v.getStore().getId().equals(storeId))
                    .orElseThrow(() -> new NoSuchElementException("Variation " + line.variationId() + " not found"));
        } else {
            productRepository.findById(line.productId())
                    .filter(p -> p.getStore().getId().equals(storeId))
                    .orElseThrow(() -> new NoSuchElementException("Product " + line.productId() + " not found"));
        }
    }

    // CartService speaks (id, type) while checkout speaks (productId, variationId) — same items,
    // two shapes that predate each other.
    private List<CartItemCommand> toCartItems(List<CartLineCommand> lines) {
        List<CartItemCommand> items = new ArrayList<>();
        for (CartLineCommand line : lines) {
            items.add(line.variationId() != null
                    ? new CartItemCommand(line.variationId(), "variation", line.quantity())
                    : new CartItemCommand(line.productId(), "product", line.quantity()));
        }
        return items;
    }

    private OrderItem buildOrderItem(Order order, CartLineCommand line) {
        Product product;
        ProductVariation variation = null;
        BigDecimal unitPrice;
        String sku;

        Long storeId = TenantContext.requireStoreId();
        if (line.variationId() != null) {
            variation = variationRepository.findById(line.variationId())
                    .filter(v -> v.getStore().getId().equals(storeId))
                    .orElseThrow(() -> new NoSuchElementException("Variation " + line.variationId() + " not found"));
            product = variation.getProduct();
            unitPrice = variation.getPrice();
            sku = variation.getSku();
        } else {
            product = productRepository.findById(line.productId())
                    .filter(p -> p.getStore().getId().equals(storeId))
                    .orElseThrow(() -> new NoSuchElementException("Product " + line.productId() + " not found"));
            unitPrice = product.getPrice();
            sku = product.getSku();
        }

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setVariation(variation);
        item.setProductName(product.getName());
        item.setProductSku(sku);
        item.setQuantity(line.quantity());
        item.setPrice(unitPrice);
        item.setSubtotal(unitPrice.multiply(BigDecimal.valueOf(line.quantity())));
        item.setTaxAmount(BigDecimal.ZERO);
        return item;
    }

    private Address buildAddress(CheckoutCommand command) {
        Address address = new Address();
        address.setFirstName(command.customerFirstName());
        address.setLastName(command.customerLastName());
        address.setPhone(command.customerPhone());
        address.setAddressLine1(command.shippingAddress().addressLine1());
        address.setAddressLine2(command.shippingAddress().addressLine2());
        address.setCity(command.shippingAddress().city());
        address.setState(command.shippingAddress().state());
        address.setPostalCode(command.shippingAddress().postalCode());
        address.setCountry(command.shippingAddress().country());
        return address;
    }

    private static String randomOrderSuffix() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(SKU_CHARS.charAt(random.nextInt(SKU_CHARS.length())));
        }
        return sb.toString();
    }

    private String stringSetting(String key, String fallback) {
        return settingRepository.findByStoreIdAndSettingKey(TenantContext.requireStoreId(), key).map(Setting::getValue).orElse(fallback);
    }

    private boolean boolSetting(String key, boolean fallback) {
        return settingRepository.findByStoreIdAndSettingKey(TenantContext.requireStoreId(), key).map(s -> Boolean.parseBoolean(s.getValue())).orElse(fallback);
    }

    private BigDecimal decimalSetting(String key, BigDecimal fallback) {
        return settingRepository.findByStoreIdAndSettingKey(TenantContext.requireStoreId(), key)
                .map(Setting::getValue)
                .map(BigDecimal::new)
                .orElse(fallback);
    }
}
