package org.uvo.uvostore.service.platform;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.audit.enums.AuditAction;
import org.uvo.uvostore.entity.audit.enums.AuditTargetType;
import org.uvo.uvostore.entity.payment.enums.PaymentGatewayType;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.entity.tenant.enums.StoreStatus;
import org.uvo.uvostore.repository.CustomerRepository;
import org.uvo.uvostore.repository.OrderRefundRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.repository.PaymentGatewayConfigRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.repository.SettingRepository;
import org.uvo.uvostore.repository.ShippingMethodRepository;
import org.uvo.uvostore.repository.StoreRepository;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.security.TokenVersionService;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.audit.AuditDiff;
import org.uvo.uvostore.service.audit.AuditEntry;
import org.uvo.uvostore.service.audit.AuditTrail;
import org.uvo.uvostore.service.pos.PosIntegrationPolicy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * PROD-23. Cierra una tienda: le quita todo lo que permite entrar o actuar en su nombre, y conserva sus datos.
 *
 * <p><b>Solo desde SUSPENDED.</b> La suspensión es el período en que termina lo que estaba en vuelo (los retornos de
 * pago pasan, los jobs y el outbox siguen), y el cierre exige que haya terminado: sin pagos de pasarela sin resolver,
 * sin cobradas a las que les falte el stock, sin mensajes del outbox pendientes y sin reembolsos de resultado
 * desconocido. Cerrar con algo de eso borraría las credenciales o la conexión que lo resuelven. Las órdenes manuales
 * pendientes no cuentan: no hay dinero en vuelo, y {@code AbandonedOrderJob} las cancela solo en la base.
 *
 * <p>En una transacción, con la fila de la tienda bloqueada:
 * <ol>
 *   <li>administradores desactivados, sin contraseña, sin tokens de invitación ni de recuperación, y sin sesiones;</li>
 *   <li>clientes igual, en una sola sentencia;</li>
 *   <li>pasarelas borradas, credenciales de transportistas en nulo, secretos de Stripe borrados, Stripe y la
 *       sincronización con el POS apagados;</li>
 *   <li>mapeos de productos y conexiones con el POS borrados: la empresa queda libre para otra alta;</li>
 *   <li>dominio liberado (el nick sigue ocupado hasta la purga);</li>
 *   <li>estado CLOSED, y una fila {@code STORE_CLOSE} con lo que se hizo.</li>
 * </ol>
 *
 * <p>Órdenes, clientes, productos, imágenes y auditoría se quedan hasta la purga, que es un procedimiento SQL
 * documentado en CLAUDE.md y no una operación de la API: borrar datos de un comerciante es irreversible y depende de
 * una retención que se acuerda fuera del código.
 *
 * <p>Las pasarelas, los transportistas y las conexiones se tocan con sentencias que no cargan la entidad: cargarla
 * descifra las credenciales, y una clave rotada dejaría la tienda sin poder cerrarse.
 */
@Service
public class StoreClosureServiceImpl implements StoreClosureService {

    /** Los secretos de Stripe que guarda {@code settings} cifrados (SecretCrypto). */
    private static final List<String> STRIPE_SECRET_KEYS = List.of("stripe_secret_key", "stripe_webhook_secret");

    /** Los interruptores que se apagan, si la tienda los tiene. */
    private static final List<String> SWITCHES_OFF = List.of("stripe_enabled", PosIntegrationPolicy.STORE_SETTING_KEY);

    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final OutboxMessageRepository outboxMessageRepository;
    private final OrderRefundRepository orderRefundRepository;
    private final PaymentGatewayConfigRepository paymentGatewayConfigRepository;
    private final ShippingMethodRepository shippingMethodRepository;
    private final SettingRepository settingRepository;
    private final PosConnectionRepository posConnectionRepository;
    private final ProductSyncMappingRepository productSyncMappingRepository;
    private final TokenVersionService tokenVersionService;
    private final AuditTrail auditTrail;
    private final PlatformStoreMapper mapper;

    public StoreClosureServiceImpl(StoreRepository storeRepository, UserRepository userRepository,
                                   CustomerRepository customerRepository, OrderRepository orderRepository,
                                   OutboxMessageRepository outboxMessageRepository,
                                   OrderRefundRepository orderRefundRepository,
                                   PaymentGatewayConfigRepository paymentGatewayConfigRepository,
                                   ShippingMethodRepository shippingMethodRepository,
                                   SettingRepository settingRepository,
                                   PosConnectionRepository posConnectionRepository,
                                   ProductSyncMappingRepository productSyncMappingRepository,
                                   TokenVersionService tokenVersionService, AuditTrail auditTrail,
                                   PlatformStoreMapper mapper) {
        this.storeRepository = storeRepository;
        this.userRepository = userRepository;
        this.customerRepository = customerRepository;
        this.orderRepository = orderRepository;
        this.outboxMessageRepository = outboxMessageRepository;
        this.orderRefundRepository = orderRefundRepository;
        this.paymentGatewayConfigRepository = paymentGatewayConfigRepository;
        this.shippingMethodRepository = shippingMethodRepository;
        this.settingRepository = settingRepository;
        this.posConnectionRepository = posConnectionRepository;
        this.productSyncMappingRepository = productSyncMappingRepository;
        this.tokenVersionService = tokenVersionService;
        this.auditTrail = auditTrail;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public PlatformStoreDto close(Long storeId, String confirmSlug, StoreStatusReason reason) {
        Store store = storeRepository.findLockedById(storeId)
                .orElseThrow(() -> new NoSuchElementException("Store " + storeId + " not found"));
        if (confirmSlug == null || !store.getSlug().equals(confirmSlug.trim())) {
            throw new BusinessException("El nick de confirmación no coincide con el de la tienda");
        }
        if (store.isClosed()) {
            return mapper.toDto(store);
        }
        if (!store.isSuspended()) {
            throw new BusinessException("Solo se puede cerrar una tienda suspendida: suspéndela primero y espera a que "
                    + "termine lo que esté en curso");
        }
        requireNothingInFlight(store.getId());

        int usersRevoked = revokeAdmins(store.getId());
        int customersRevoked = customerRepository.revokeAccessForStore(store.getId(), Instant.now());

        List<PaymentGatewayType> gateways = paymentGatewayConfigRepository.findGatewaysByStoreId(store.getId());
        paymentGatewayConfigRepository.deleteAllForStore(store.getId());
        List<String> shippingMethods = shippingMethodRepository.findCodesWithCredentialsForStore(store.getId());
        shippingMethodRepository.clearCredentialsForStore(store.getId());
        int stripeSecretsRemoved = removeStripeSecrets(store.getId());

        List<Long> posCompanyIds = posConnectionRepository.findCompanyIdsByStoreId(store.getId());
        int syncMappingsRemoved = productSyncMappingRepository.deleteAllForStore(store.getId());
        posConnectionRepository.deleteAllForStore(store.getId());

        String previousDomain = store.getDomain();
        store.setDomain(null);
        store.setDomainVerifiedAt(null);
        store.setStatus(StoreStatus.CLOSED);
        store.setStatusChangedAt(Instant.now());
        storeRepository.save(store);

        // Nombres y cantidades: ni una credencial, ni un correo de cliente.
        auditTrail.record(AuditEntry.of(AuditAction.STORE_CLOSE, AuditTargetType.STORE, store.getId())
                .forStore(store.getId())
                .details(AuditDiff.create()
                        .value("status", StoreStatus.SUSPENDED, StoreStatus.CLOSED)
                        .with("reason", reason)
                        .with("previousDomain", previousDomain)
                        .with("usersRevoked", usersRevoked)
                        .with("customersRevoked", customersRevoked)
                        .with("gatewaysRemoved", gateways)
                        .with("shippingCredentialsRemoved", shippingMethods)
                        .with("stripeSecretsRemoved", stripeSecretsRemoved)
                        .with("posCompanyIds", posCompanyIds)
                        .with("syncMappingsRemoved", syncMappingsRemoved)));
        return mapper.toDto(store);
    }

    /** Lo que impide cerrar, con cuántos hay de cada cosa: el operador tiene que saber qué resolver. */
    private void requireNothingInFlight(Long storeId) {
        List<String> pending = new ArrayList<>();
        addIfAny(pending, orderRepository.countGatewayPaymentsPendingForStore(storeId), "pagos de pasarela sin resolver");
        addIfAny(pending, orderRepository.countPaidWithoutStockAppliedForStore(storeId), "órdenes pagadas sin el stock descontado");
        addIfAny(pending, outboxMessageRepository.countUnfinishedForStore(storeId), "envíos pendientes (venta al POS o correos)");
        addIfAny(pending, orderRefundRepository.countPendingForStore(storeId), "reembolsos de resultado desconocido");
        if (!pending.isEmpty()) {
            throw new BusinessException("La tienda tiene operaciones en curso: " + String.join(", ", pending)
                    + ". Resuélvelas antes de cerrarla.");
        }
    }

    private static void addIfAny(List<String> pending, long count, String what) {
        if (count > 0) {
            pending.add(count + " " + what);
        }
    }

    /**
     * Uno por uno y no en masa: son pocos por tienda, y así cada uno sale también de la caché de versiones de
     * {@code TokenVersionService}. Desde el commit el Host responde 403 "cerrada", así que esto es la segunda capa: si la
     * tienda volviera a abrirse por error, ningún token viejo serviría.
     */
    private int revokeAdmins(Long storeId) {
        List<User> admins = userRepository.findAllByStoreIdOrderById(storeId);
        for (User admin : admins) {
            admin.setActive(false);
            admin.setPassword(null);
            admin.setInvitationToken(null);
            admin.setPasswordResetToken(null);
            admin.setPasswordResetExpiresAt(null);
            admin.setTokenVersion(admin.getTokenVersion() + 1);
            tokenVersionService.evict(TokenVersionService.ADMIN, admin.getId());
        }
        userRepository.saveAll(admins);
        return admins.size();
    }

    private int removeStripeSecrets(Long storeId) {
        int removed = 0;
        for (String key : STRIPE_SECRET_KEYS) {
            var setting = settingRepository.findByStoreIdAndSettingKey(storeId, key);
            if (setting.isPresent()) {
                settingRepository.delete(setting.get());
                removed++;
            }
        }
        for (String key : SWITCHES_OFF) {
            settingRepository.findByStoreIdAndSettingKey(storeId, key).ifPresent(setting -> {
                setting.setValue("false");
                settingRepository.save(setting);
            });
        }
        return removed;
    }
}
