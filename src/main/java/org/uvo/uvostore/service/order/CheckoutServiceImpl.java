package org.uvo.uvostore.service.order;

import org.uvo.uvostore.service.BusinessException;
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
import org.uvo.uvostore.repository.ShippingMethodRepository;
import org.uvo.uvostore.repository.ShippingRateRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.customer.CustomerService;
import org.uvo.uvostore.service.geo.GeoCatalog;
import org.uvo.uvostore.service.outbox.OutboxService;
import org.uvo.uvostore.service.pos.PosSaleCoverage;
import org.uvo.uvostore.service.settings.SettingValues;
import org.uvo.uvostore.service.shipping.ShippingOption;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.NoSuchElementException;

// Consolidates the checkout order-creation path onto the single CartPricingService calculator
// (see CartPricingServiceImpl) instead of CheckoutController::store()'s own hardcoded
// subtotal/shipping/tax math — that duplication (and its client-trusted item price) is exactly
// what services.md's "three money calculators" note flags as a bug, not a pattern to replicate.
@Service
public class CheckoutServiceImpl implements CheckoutService {

    private static final String SKU_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final String REPLACED_BY_CURRENT_PRICE = "Reemplazada por una orden con el precio vigente";

    private final CartPricingService cartPricingService;
    private final CartService cartService;
    private final CouponService couponService;
    private final CustomerService customerService;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProductVariationRepository variationRepository;
    private final SettingRepository settingRepository;
    private final PaymentGatewayConfigRepository paymentGatewayConfigRepository;
    private final OutboxService outboxService;
    private final ShippingMethodRepository shippingMethodRepository;
    private final ShippingRateRepository shippingRateRepository;
    private final GeoCatalog geoCatalog;
    private final PosSaleCoverage posSaleCoverage;
    private final OrderStatusService orderStatusService;
    private final int retryWindowMinutes;

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
            OutboxService outboxService,
            ShippingMethodRepository shippingMethodRepository,
            ShippingRateRepository shippingRateRepository,
            GeoCatalog geoCatalog,
            PosSaleCoverage posSaleCoverage,
            OrderStatusService orderStatusService,
            @org.springframework.beans.factory.annotation.Value("${app.abandoned-orders.retry-window-minutes:60}") int retryWindowMinutes) {
        this.cartPricingService = cartPricingService;
        this.cartService = cartService;
        this.couponService = couponService;
        this.customerService = customerService;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.variationRepository = variationRepository;
        this.settingRepository = settingRepository;
        this.paymentGatewayConfigRepository = paymentGatewayConfigRepository;
        this.outboxService = outboxService;
        this.shippingMethodRepository = shippingMethodRepository;
        this.shippingRateRepository = shippingRateRepository;
        this.geoCatalog = geoCatalog;
        this.posSaleCoverage = posSaleCoverage;
        this.orderStatusService = orderStatusService;
        this.retryWindowMinutes = retryWindowMinutes;
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
            // GAP-06: validateItems también rechaza, con el POS activo, una orden de dos empresas o con productos
            // sin mapeo. Si eso es todo lo que falla, el mensaje lo dice; si además falta stock, manda el de stock.
            Map<String, String> posErrors = posSaleCoverage.lineErrors(TenantContext.requireCurrent(), command.lines());
            if (posErrors.entrySet().containsAll(stockCheck.errors().entrySet())) {
                throw new OutOfStockException(
                        "Algunos productos no se pueden comprar en este pedido. Revisa el detalle de cada uno.",
                        stockCheck.errors());
            }
            throw new OutOfStockException(stockCheck.errors());
        }

        // F05: el cliente se resuelve ANTES de cotizar, no después. El precio depende de quién compra
        // —el límite de usos por cliente de un cupón—, así que cotizar primero y averiguar el cliente
        // después obligaba a decidir dos veces sobre el mismo cupón, y las dos decisiones podían no
        // coincidir. Todo esto va en una transacción: si el checkout falla más abajo, el invitado
        // recién creado se deshace con el resto.
        Customer customer = customerService.findOrCreateGuest(
                command.customerEmail(), command.customerFirstName(), command.customerLastName(), command.customerPhone());
        customer = customerService.markInvitedIfGuest(customer);

        // F15. El reintento de la misma compra devuelve la orden que ya existe, y se comprueba ANTES de
        // cotizar. El orden importa: si se cotizara primero, la validación del cupón vería la reserva que
        // hizo el primer intento, la rechazaría por límite por cliente y el checkout moriría con un 400
        // (F05) sin llegar nunca hasta aquí. Era justo el caso que hay que arreglar.
        //
        // El frontend crea la orden y solo después llama a la pasarela (useCheckoutStore), así que un
        // fallo al abrir la sesión de pago deja una orden PENDING y el carrito intacto: el siguiente clic
        // vuelve a entrar por este método. Y esas órdenes no las mira nadie — la conciliación exige un id
        // de pasarela y estas no llegaron a tenerlo.
        Optional<Order> retried = findReusablePendingOrder(customer, command);
        if (retried.isPresent()) {
            Order existing = retried.get();
            if (command.expectedTotal() == null || existing.getTotal().compareTo(command.expectedTotal()) == 0) {
                // No se vuelve a cotizar ni a reclamar el cupón: la reserva ya es de esta orden, y su total es
                // el precio que el cliente ve. Tampoco se encola otro acuse de recibo, que mandaría un segundo
                // "recibimos tu pedido".
                return new OrderConfirmation(existing.getId(), existing.getOrderNumber(), existing.getTotal());
            }
            // GAP-07: el comprador ve otro total, así que el precio cambió desde el primer intento. Se crea una
            // orden con el precio de hoy. La vieja se cancela antes de cotizar, para que su reserva de cupón no
            // cuente contra la nueva; si la compra falla más abajo, el rollback la deja como estaba. Solo si
            // nunca abrió una sesión de pago: si la abrió, el comprador podría estar pagándola en otra pestaña,
            // y cancelarla dejaría ese pago sin orden. Esa queda para la conciliación y para AbandonedOrderJob.
            if (existing.getPaymentId() == null && existing.getStripeCheckoutSessionId() == null) {
                orderStatusService.markCancelled(existing.getId(), REPLACED_BY_CURRENT_PRICE);
            }
        }

        CartTotals totals = cartPricingService.price(command.lines(), command.couponCode(), command.region(),
                command.commune(), customer.getId());

        // A7: refuse instead of creating an order the merchant can't dispatch. Every order used to
        // ship for $0 here — the SPA never sent a region, so no zone matched and the cost fell
        // through to zero silently. Server-side on purpose: hiding the button in the UI wouldn't
        // stop a direct API call.
        if (!totals.shippingAvailable()) {
            throw new ShippingUnavailableException(command.region(), command.commune());
        }

        // F05: el carrito no sabe quién compra, así que pudo mostrar un descuento que a este cliente
        // no le corresponde. Se rechaza en vez de cobrar la diferencia sin avisar. Solo aquí: un
        // código inexistente o caducado tampoco se aplicó en la cotización, no hay nada que explicar y
        // la compra sigue a precio completo, como hasta ahora.
        if (totals.customerRejectionReason() != null) {
            throw new BusinessException(totals.customerRejectionReason());
        }

        // GAP-07: se cobra el total que el comprador vio al confirmar, o nada. Entre la cotización del carrito y
        // este punto pudo vencer una oferta o cambiar un precio en el POS o en el panel; cobrar la diferencia sin
        // avisar es justo lo que no debe pasar, y en el pago manual ni siquiera hay una pasarela que muestre el
        // monto. El SPA recotiza con el 409 y el comprador acepta el total nuevo.
        if (command.expectedTotal() != null && totals.total().compareTo(command.expectedTotal()) != 0) {
            throw new PriceChangedException(command.expectedTotal(), totals.total());
        }

        Order order = new Order();
        order.setStore(TenantContext.requireCurrent());
        order.setOrderNumber("ORD-" + randomOrderSuffix());
        order.setCustomer(customer);
        order.setCustomerEmail(command.customerEmail());
        order.setCustomerFirstName(command.customerFirstName());
        order.setCustomerLastName(command.customerLastName());
        order.setCustomerPhone(command.customerPhone());

        // GAP-05: con precios con IVA incluido, el subtotal se guarda como lo vio el comprador y el IVA queda
        // contenido; así subtotal − descuento + envío da el total, y las líneas suman el subtotal.
        order.setTaxIncluded(totals.taxIncluded());
        order.setSubtotal(totals.taxIncluded() ? totals.subtotalWithTax() : totals.subtotalWithoutTax());
        order.setDiscountAmount(totals.discountAmount());
        order.setShippingCost(totals.shippingCost());
        order.setTaxAmount(totals.taxAmount());
        order.setTotal(totals.total());

        order.setStatus(OrderStatus.PENDING);
        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);
        order.setPaymentMethod(org.uvo.uvostore.entity.order.enums.PaymentMethodType.valueOf(command.paymentMethod().toUpperCase()));
        order.setCustomerNotes(command.customerNotes());

        Address address = buildAddress(command);
        order.setShippingAddress(address);
        order.setBillingAddress(address);
        // Catálogo de comunas: el nombre canónico con que se cotizó. Si no se reconoce —una tienda con el envío
        // apagado no exige cobertura—, el texto tal como llegó, que es lo que se guardaba antes.
        order.setShippingRegion(geoCatalog.resolveRegion(command.region()).orElse(command.region()));
        order.setShippingCommune(geoCatalog.resolveCommune(command.region(), command.commune()).orElse(command.commune()));
        order.setShippingPostalCode(command.shippingAddress().postalCode());
        applyShipping(order, totals.appliedShipping());

        // F05: aquí ya no se vuelve a validar nada. El cupón es el que entró en el total, decidido en
        // el mismo cálculo que lo fijó — antes esta segunda validación podía discrepar de la primera y
        // el `if` sin `else` se limitaba a no adjuntar el cupón, dejando la orden rebajada, sin cupón
        // y sin uso registrado: un descuento que ningún contador veía y que se repetía en cada compra.
        if (totals.appliedCoupon() != null) {
            // Claimed here, before the order is built, because the discount is already baked
            // into `totals` above. If the coupon ran out in the meantime we can't quietly
            // re-price without it — the customer would be charged an amount they never saw —
            // so the checkout fails and they can retry. The claim is part of this transaction,
            // so any later failure rolls it back.
            if (!couponService.claimUsage(totals.appliedCoupon())) {
                throw new BusinessException("El cupón alcanzó su límite de usos.");
            }
            order.setCoupon(totals.appliedCoupon());
            order.setCouponCode(command.couponCode());
        }

        List<OrderItem> items = new ArrayList<>();
        for (int i = 0; i < command.lines().size(); i++) {
            items.add(buildOrderItem(order, command.lines().get(i), totals.lines().get(i)));
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

        // F07: el pedido se ha hecho, no se ha pagado. De aquí cuelgan solo el acuse de recibo y, con las
        // cuentas encendidas, la invitación; la venta al POS —que emite un documento tributario— y el correo
        // de compra confirmada los encola markPaid. PROD-13b: en el outbox y en esta misma transacción, así
        // que si el checkout se deshace no sale ningún correo, y si se confirma sale aunque el proceso caiga.
        outboxService.enqueueOrderPlaced(saved, customer);

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

    /**
     * F16. Deja escrito en la orden con qué se cotizó el envío.
     *
     * <p>Las cuatro columnas existían en {@code orders} desde el principio y <b>nadie las rellenaba</b>, así
     * que no solo se perdía la trazabilidad —el comerciante no podía saber qué transportista ni qué plazo
     * respaldaron el precio— sino que dos comprobaciones quedaban inertes:
     * {@code AdminShippingZoneServiceImpl} y {@code AdminShippingMethodServiceImpl} se niegan a borrar una
     * zona o un método "con órdenes asociadas", y como ninguna orden apuntaba a ninguno, siempre
     * contaban cero. Se podía borrar la zona que explicaba el precio de una venta.
     *
     * <p>El nombre se guarda además como texto en {@code shipping_method} a propósito: es el único dato
     * que sobrevive si el método se borra más adelante.
     *
     * <p>{@code rateId} es nulo cuando el precio vino de una cotización en vivo de transportista, y
     * entonces tampoco hay zona que anotar: la zona la aporta la tarifa (un {@code ShippingMethod} no
     * pertenece a ninguna), y una cotización en vivo no pasa por la tabla de tarifas. Queda el método y
     * su nombre, que es lo que hay.
     */
    private void applyShipping(Order order, ShippingOption shipping) {
        if (shipping == null) {
            return;
        }
        order.setShippingMethod(shipping.methodName());
        if (shipping.methodId() != null) {
            shippingMethodRepository.findById(shipping.methodId()).ifPresent(order::setShippingMethodRef);
        }
        if (shipping.rateId() != null) {
            shippingRateRepository.findById(shipping.rateId()).ifPresent(rate -> {
                order.setShippingRate(rate);
                order.setShippingZone(rate.getZone());
            });
        }
    }

    /**
     * F15. La orden pendiente de este mismo cliente que corresponde a <b>esta misma compra</b>, si la hay.
     *
     * <p>Tres condiciones, y las tres importan:
     * <ul>
     *   <li><b>Mismas líneas</b>: mismo producto o variación y misma cantidad, sin importar el orden en
     *       que lleguen.</li>
     *   <li><b>Mismo cupón</b>, incluido "ninguno en ninguna de las dos".</li>
     *   <li><b>Reciente.</b> Es la que acota el riesgo. Aquí no se recotiza para comparar —no se puede: el
     *       cupón que reservó el primer intento ya está gastado, así que el importe nuevo nunca coincidiría
     *       con el de la orden—, y a cambio la ventana es corta. Reintentar un pago pasa en minutos; pasado
     *       el plazo se crea una orden nueva con el precio de hoy y de la vieja se encarga
     *       {@code AbandonedOrderJob}.</li>
     * </ul>
     *
     * <p><b>Cuándo se respeta el precio congelado (GAP-07).</b> Lo que sí se compara es el total de la orden
     * con el que el comprador ve ({@code expectedTotal}), en {@code checkout}: la orden se reutiliza solo si
     * coinciden. Si el precio cambió, el comprador está viendo otro total, y se cobra ese, el vigente: la
     * orden vieja se reemplaza. Una orden ya creada sí se cobra siempre por su total en la pasarela, que es el
     * que el comprador aceptó al crearla. Sin {@code expectedTotal} (clientes de la API anteriores al campo)
     * no hay con qué comparar, y se reutiliza como antes.
     *
     * <p>Solo se mira la más reciente: si hubiera varias pendientes iguales (de antes de este arreglo),
     * reutilizar la última es lo razonable y el job se encarga del resto.
     */
    private Optional<Order> findReusablePendingOrder(Customer customer, CheckoutCommand command) {
        Instant notBefore = Instant.now().minus(Duration.ofMinutes(retryWindowMinutes));
        return orderRepository.findByCustomerIdOrderByCreatedAtDesc(customer.getId()).stream()
                .filter(o -> o.getStatus() == OrderStatus.PENDING && o.getPaymentStatus() == PaymentStatus.PENDING)
                .filter(o -> o.getCreatedAt() != null && o.getCreatedAt().isAfter(notBefore))
                .filter(o -> sameCoupon(o, command.couponCode()))
                .filter(o -> sameLines(o, command.lines()))
                .findFirst();
    }

    private boolean sameCoupon(Order order, String couponCode) {
        String existing = order.getCouponCode();
        if (existing == null || existing.isBlank()) {
            return couponCode == null || couponCode.isBlank();
        }
        return existing.equals(couponCode);
    }

    private boolean sameLines(Order order, List<CartLineCommand> lines) {
        Map<String, Integer> requested = new HashMap<>();
        for (CartLineCommand line : lines) {
            String key = line.variationId() != null ? "v:" + line.variationId() : "p:" + line.productId();
            requested.merge(key, line.quantity(), Integer::sum);
        }
        Map<String, Integer> stored = new HashMap<>();
        for (OrderItem item : order.getItems()) {
            String key = item.getVariation() != null
                    ? "v:" + item.getVariation().getId()
                    : "p:" + item.getProduct().getId();
            stored.merge(key, item.getQuantity(), Integer::sum);
        }
        return requested.equals(stored);
    }

    private OrderItem buildOrderItem(Order order, CartLineCommand line, LineAmounts amounts) {
        Product product;
        ProductVariation variation = null;
        String sku;

        Long storeId = TenantContext.requireStoreId();
        if (line.variationId() != null) {
            variation = variationRepository.findById(line.variationId())
                    .filter(v -> v.getStore().getId().equals(storeId))
                    .orElseThrow(() -> new NoSuchElementException("Variation " + line.variationId() + " not found"));
            product = variation.getProduct();
            sku = variation.getSku();
        } else {
            product = productRepository.findById(line.productId())
                    .filter(p -> p.getStore().getId().equals(storeId))
                    .orElseThrow(() -> new NoSuchElementException("Product " + line.productId() + " not found"));
            // F09: el cinturón. La validación del carrito ya rechaza la ficha padre de un producto
            // variable con un error por línea, y aquí se vuelve a comprobar antes de escribir el
            // OrderItem — por si mañana alguien reordena las llamadas de checkout() y esta línea deja de
            // pasar por la validación.
            CartPricingServiceImpl.requireSimpleProduct(product);
            sku = product.getSku();
        }

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setVariation(variation);
        item.setProductName(product.getName());
        item.setProductSku(sku);
        item.setQuantity(line.quantity());
        // F11: el snapshot del OrderItem es lo que se cobra y lo que va al documento del POS, así que tiene que
        // llevar el precio vigente, el de oferta si la hay. GAP-07: y se copia de la cotización en vez de volver a
        // leer el producto. Una escritura del POS o del panel entre las dos lecturas dejaba líneas que no
        // sumaban el total de la orden.
        item.setPrice(amounts.unitPrice());
        item.setSubtotal(amounts.subtotal());
        // GAP-05: la parte del descuento y del IVA de la orden que le toca a esta línea, como la repartió
        // CartPricingServiceImpl. Antes el IVA de la línea era siempre cero y las líneas no sumaban la orden.
        item.setDiscountAmount(amounts.discount());
        item.setTaxAmount(amounts.tax());
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

    // F17: era .map(BigDecimal::new) crudo, y este es el sitio que la SPA pide para pintar la página de
    // checkout: un ajuste mal tecleado mataba la compra antes de que el cliente escribiera nada.
    private BigDecimal decimalSetting(String key, BigDecimal fallback) {
        return SettingValues.decimal(key,
                settingRepository.findByStoreIdAndSettingKey(TenantContext.requireStoreId(), key)
                        .map(Setting::getValue).orElse(null),
                fallback);
    }
}
