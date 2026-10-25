package org.uvo.uvostore.service.settings;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.audit.enums.AuditAction;
import org.uvo.uvostore.entity.audit.enums.AuditTargetType;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.settings.Setting;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.SettingRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.audit.AuditDiff;
import org.uvo.uvostore.service.audit.AuditEntry;
import org.uvo.uvostore.service.audit.AuditTrail;
import org.uvo.uvostore.service.pos.PosApiKey;
import org.uvo.uvostore.service.pos.PosIntegrationPolicy;

@Service
public class SettingsServiceImpl implements SettingsService {

    private final SettingRepository settingRepository;
    private final PosConnectionRepository posConnectionRepository;
    private final PosIntegrationPolicy posPolicy;
    private final AuditTrail auditTrail;

    public SettingsServiceImpl(SettingRepository settingRepository, PosConnectionRepository posConnectionRepository,
                               PosIntegrationPolicy posPolicy, AuditTrail auditTrail) {
        this.settingRepository = settingRepository;
        this.posConnectionRepository = posConnectionRepository;
        this.posPolicy = posPolicy;
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional(readOnly = true)
    public GeneralSettingsDto getGeneralSettings() {
        PosConnection connection = posConnectionRepository.findByStoreId(TenantContext.requireStoreId()).orElse(null);
        return new GeneralSettingsDto(
                get("store_name", "UvoStore"), get("store_email", ""), get("store_phone", ""), get("admin_email", ""),
                get("currency", "CLP"), get("currency_symbol", "$"), get("tax_rate", "19"), getBool("prices_include_tax", false),
                getBool("shipping_enabled", true), get("default_shipping_cost", "0"), getBool("free_shipping_enabled", false),
                get("free_shipping_threshold", "0"), getBool("allow_guest_checkout", true), getBool("require_phone", false),
                getBool("require_company", false), get("stripe_public_key", ""), isSet("stripe_secret_key"),
                getBool("stripe_enabled", false), get("pos_api_url", ""),
                connection != null && notBlank(connection.getApiKey()), connection != null && notBlank(connection.getWebhookSecret()),
                getBool("pos_sync_enabled", false), posPolicy.isEnabled(),
                get("meta_title", ""), get("meta_description", ""), get("meta_keywords", ""),
                get("facebook_url", ""), get("instagram_url", ""), get("twitter_url", "")
        );
    }

    @Override
    @Transactional
    public GeneralSettingsDto updateGeneralSettings(GeneralSettingsUpdateRequest command) {
        String posToken = command.posApiToken() == null ? "" : command.posApiToken().trim();

        // F17. Todo lo que participa en dinero se valida y se normaliza ANTES de escribir nada. El
        // método es transaccional, así que un rechazo aquí deshace lo ya escrito de todos modos, pero
        // resolverlo primero deja el orden explícito: la tienda no queda a medio configurar por un
        // campo mal tecleado. El rango y el catálogo viven en SettingValues, que es también quien lee
        // estos ajustes al cotizar.
        String currency = SettingValues.normalizedCurrency(command.currency());
        String taxRate = SettingValues.normalizedTaxRate(command.taxRate());
        String defaultShippingCost = SettingValues.normalizedAmount(
                command.defaultShippingCost(), "el costo de envío por defecto");
        String freeShippingThreshold = SettingValues.normalizedAmount(
                command.freeShippingThreshold(), "el monto mínimo para envío gratis");
        requireStripeCredentials(command);

        // PROD-18. El formulario se guarda entero cada vez, así que set() devuelve lo que había y la auditoría se
        // queda con lo que cambió, en tres grupos: dinero (F17), Stripe y POS. El resto (nombre, SEO, redes) no se
        // audita.
        AuditDiff money = AuditDiff.create();
        AuditDiff stripe = AuditDiff.create();
        AuditDiff pos = AuditDiff.create();
        boolean stripeSecretWasSet = isSet("stripe_secret_key");

        set("store_name", command.storeName());
        set("store_email", command.storeEmail());
        set("store_phone", command.storePhone());
        set("admin_email", command.adminEmail());
        setAudited(money, "currency", currency);
        setAudited(money, "currency_symbol", command.currencySymbol().trim());
        setAudited(money, "tax_rate", taxRate);
        setAudited(money, "prices_include_tax", String.valueOf(command.pricesIncludeTax()));
        setAudited(money, "shipping_enabled", String.valueOf(command.shippingEnabled()));
        setAudited(money, "default_shipping_cost", defaultShippingCost);
        setAudited(money, "free_shipping_enabled", String.valueOf(command.freeShippingEnabled()));
        setAudited(money, "free_shipping_threshold", freeShippingThreshold);
        set("allow_guest_checkout", String.valueOf(command.allowGuestCheckout()));
        set("require_phone", String.valueOf(command.requirePhone()));
        set("require_company", String.valueOf(command.requireCompany()));
        // La clave pública no es secreta, pero se registra igual que las credenciales de las pasarelas: solo si cambió.
        stripe.secret("stripe_public_key", set("stripe_public_key", command.stripePublicKey()), command.stripePublicKey());
        stripe.secretSubmitted("stripe_secret_key", stripeSecretWasSet, setSecret("stripe_secret_key", command.stripeSecretKey()));
        setAudited(stripe, "stripe_enabled", String.valueOf(command.stripeEnabled()));
        setAudited(pos, "pos_api_url", command.posApiUrl());
        setAudited(pos, "pos_sync_enabled", String.valueOf(command.posSyncEnabled()));
        set("meta_title", command.metaTitle());
        set("meta_description", command.metaDescription());
        set("meta_keywords", command.metaKeywords());
        set("facebook_url", command.facebookUrl());
        set("instagram_url", command.instagramUrl());
        set("twitter_url", command.twitterUrl());

        // POS integration is optional — only validate/upsert the connection when the admin
        // actually supplied a token, so saving unrelated settings (colors, shipping, checkout
        // options) never fails just because the store hasn't set up UvoPOS yet. The token/secret
        // are never stored in the plain-text `settings` table — only on PosConnection, whose
        // apiKey/webhookSecret columns are encrypted at rest (see EncryptedStringConverter).
        if (!posToken.isEmpty()) {
            Long companyId = PosApiKey.companyIdOf(posToken)
                    .orElseThrow(() -> new IllegalArgumentException("API key inválida"));
            Store store = TenantContext.requireCurrent();
            PosConnection connection = posConnectionRepository.findByStoreId(store.getId()).orElseGet(PosConnection::new);
            // PROD-18. Lo de antes, en claro y en memoria (EncryptedStringConverter ya lo descifró al cargar).
            boolean created = connection.getId() == null;
            Long previousCompanyId = connection.getCompanyId();
            String previousApiKey = connection.getApiKey();
            String previousWebhookSecret = connection.getWebhookSecret();
            boolean wasActive = connection.isActive();
            connection.setStore(store);
            connection.setCompanyId(companyId);
            connection.setApiKey(command.posApiToken());
            if (notBlank(command.posWebhookSecret())) {
                connection.setWebhookSecret(command.posWebhookSecret());
            } else if (connection.getWebhookSecret() == null) {
                connection.setWebhookSecret("");
            }
            requirePosWebhookSecret(connection);
            connection.setActive(true);
            if (connection.getCompanyName() == null) {
                connection.setCompanyName("UvoPOS");
            }
            if (connection.getApiUrl() == null) {
                connection.setApiUrl(command.posApiUrl() == null ? "" : command.posApiUrl());
            }
            PosConnection saved = posConnectionRepository.save(connection);
            pos.value("companyId", previousCompanyId, saved.getCompanyId())
                    .secret("apiKey", previousApiKey, saved.getApiKey())
                    .secret("webhookSecret", previousWebhookSecret, saved.getWebhookSecret())
                    .value("active", wasActive, saved.isActive());
            if (created) {
                pos.with("connectionCreated", true);
            }
        }

        recordIfChanged(AuditAction.SETTINGS_MONEY_UPDATE, money);
        recordIfChanged(AuditAction.SETTINGS_STRIPE_UPDATE, stripe);
        if (pos.hasChanges()) {
            Long storeId = TenantContext.requireStoreId();
            PosConnection connection = posConnectionRepository.findByStoreId(storeId).orElse(null);
            auditTrail.record(connection == null
                    ? AuditEntry.of(AuditAction.POS_CONNECTION_UPDATE, AuditTargetType.STORE, storeId).details(pos)
                    : AuditEntry.of(AuditAction.POS_CONNECTION_UPDATE, AuditTargetType.POS_CONNECTION, connection.getId())
                            .details(pos));
        }

        return getGeneralSettings();
    }

    // Escribe el ajuste y deja en la auditoría su antes y después, si cambió.
    private void setAudited(AuditDiff diff, String key, String value) {
        diff.value(key, set(key, value), value);
    }

    private void recordIfChanged(AuditAction action, AuditDiff diff) {
        if (diff.hasChanges()) {
            Long storeId = TenantContext.requireStoreId();
            auditTrail.record(AuditEntry.of(action, AuditTargetType.STORE, storeId).details(diff));
        }
    }

    /**
     * F17. Activar Stripe exige tener con qué cobrar, igual que M3 exige el secreto de webhook para
     * activar MercadoPago ({@code AdminPaymentGatewayServiceImpl}) y por la misma razón: se rechaza en
     * el punto de configuración, donde la persona puede hacer algo al respecto. Sin esto,
     * {@code stripe_enabled = true} sin clave secreta deja en la SPA un botón de pago que responde 500
     * —la excepción de autenticación de Stripe se envuelve en {@code IllegalStateException}—, y el
     * cliente lo descubre con el carrito lleno.
     *
     * <p>La clave secreta cuenta si <b>llega o ya estaba guardada</b>: un guardado que no toca el campo
     * de secreto (que el panel nunca puede pre-rellenar) no puede fallar por eso. Es la misma regla que
     * {@link #setSecret}.
     */
    /**
     * PROD-12. Activar la conexión al POS exige el secreto con el que se firman sus webhooks, igual que
     * F17 exige credenciales para activar Stripe y M3 para MercadoPago, y por la misma razón: se rechaza
     * donde la persona puede hacer algo al respecto.
     *
     * <p>Antes la conexión quedaba {@code active = true} de forma incondicional, con el secreto en cadena
     * vacía si el administrador dejaba el campo en blanco. Y eso no deja la puerta abierta, la deja
     * <b>rota</b>: {@code PosWebhookAuthFilter} no comprueba que el secreto exista antes de firmar, así
     * que cada webhook entrante de esa tienda muere al construir el HMAC — desde un filtro, donde
     * {@code GlobalExceptionHandler} no llega. Una conexión que se presenta como activa y no puede
     * autenticar ni una petición es peor que no tenerla.
     *
     * <p>Se comprueba sobre la conexión y no sobre el comando porque el secreto cuenta si <b>llega o ya
     * estaba guardado</b>: el panel nunca puede pre-rellenar un secreto, así que un guardado que no toca
     * ese campo no debe fallar por él. Misma regla que {@link #requireStripeCredentials}.
     */
    private void requirePosWebhookSecret(PosConnection connection) {
        if (notBlank(connection.getWebhookSecret())) {
            return;
        }
        throw new BusinessException(
                "Para conectar UvoPOS debes indicar el secreto de webhook: es con lo que se firman las "
                        + "notificaciones que llegan del POS, y sin él ninguna se puede verificar.");
    }

    private void requireStripeCredentials(GeneralSettingsUpdateRequest command) {
        if (!command.stripeEnabled()) {
            return;
        }
        boolean hasSecret = notBlank(command.stripeSecretKey()) || isSet("stripe_secret_key");
        boolean hasPublicKey = notBlank(command.stripePublicKey());
        if (!hasSecret || !hasPublicKey) {
            throw new BusinessException(
                    "Para activar Stripe debes configurar la clave pública y la clave secreta "
                            + "(Developers > API keys en el panel de Stripe).");
        }
    }

    private String get(String key, String fallback) {
        return settingRepository.findByStoreIdAndSettingKey(TenantContext.requireStoreId(), key).map(Setting::getValue).orElse(fallback);
    }

    private boolean getBool(String key, boolean fallback) {
        return settingRepository.findByStoreIdAndSettingKey(TenantContext.requireStoreId(), key).map(s -> Boolean.parseBoolean(s.getValue())).orElse(fallback);
    }

    private boolean isSet(String key) {
        return settingRepository.findByStoreIdAndSettingKey(TenantContext.requireStoreId(), key).map(Setting::getValue).filter(SettingsServiceImpl::notBlank).isPresent();
    }

    // Devuelve lo que había (null si el ajuste no existía), para la auditoría.
    private String set(String key, String value) {
        Store store = TenantContext.requireCurrent();
        Setting setting = settingRepository.findByStoreIdAndSettingKey(store.getId(), key).orElseGet(Setting::new);
        String previous = setting.getValue();
        setting.setStore(store);
        setting.setSettingKey(key);
        setting.setValue(value);
        settingRepository.save(setting);
        return previous;
    }

    // Encrypts before persisting (AES-256-GCM, see SecretCrypto) and, unlike set(), leaves the
    // stored value untouched when the incoming value is blank — the admin UI can't pre-fill this
    // field with the real secret (it's never returned by getGeneralSettings), so a save that
    // doesn't touch it must not wipe out what's already configured.
    //
    // Devuelve si se guardó uno nuevo. La auditoría no compara con el anterior: habría que descifrarlo, y si la
    // clave de cifrado rotó eso falla justo en el guardado que lo arregla.
    private boolean setSecret(String key, String value) {
        if (!notBlank(value)) {
            return false;
        }
        set(key, SecretCrypto.encrypt(value));
        return true;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
