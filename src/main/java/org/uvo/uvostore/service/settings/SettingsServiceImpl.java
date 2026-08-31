package org.uvo.uvostore.service.settings;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.settings.Setting;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.SettingRepository;
import org.uvo.uvostore.security.TenantContext;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SettingsServiceImpl implements SettingsService {

    private static final Pattern API_KEY_PATTERN = Pattern.compile("^uvp_(\\d+)_([A-Za-z0-9]+)$");

    private final SettingRepository settingRepository;
    private final PosConnectionRepository posConnectionRepository;

    public SettingsServiceImpl(SettingRepository settingRepository, PosConnectionRepository posConnectionRepository) {
        this.settingRepository = settingRepository;
        this.posConnectionRepository = posConnectionRepository;
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
                getBool("pos_sync_enabled", false), get("meta_title", ""), get("meta_description", ""), get("meta_keywords", ""),
                get("facebook_url", ""), get("instagram_url", ""), get("twitter_url", "")
        );
    }

    @Override
    @Transactional
    public GeneralSettingsDto updateGeneralSettings(GeneralSettingsUpdateRequest command) {
        String posToken = command.posApiToken() == null ? "" : command.posApiToken().trim();

        set("store_name", command.storeName());
        set("store_email", command.storeEmail());
        set("store_phone", command.storePhone());
        set("admin_email", command.adminEmail());
        set("currency", command.currency());
        set("currency_symbol", command.currencySymbol());
        set("tax_rate", command.taxRate());
        set("prices_include_tax", String.valueOf(command.pricesIncludeTax()));
        set("shipping_enabled", String.valueOf(command.shippingEnabled()));
        set("default_shipping_cost", command.defaultShippingCost());
        set("free_shipping_enabled", String.valueOf(command.freeShippingEnabled()));
        set("free_shipping_threshold", command.freeShippingThreshold());
        set("allow_guest_checkout", String.valueOf(command.allowGuestCheckout()));
        set("require_phone", String.valueOf(command.requirePhone()));
        set("require_company", String.valueOf(command.requireCompany()));
        set("stripe_public_key", command.stripePublicKey());
        setSecret("stripe_secret_key", command.stripeSecretKey());
        set("stripe_enabled", String.valueOf(command.stripeEnabled()));
        set("pos_api_url", command.posApiUrl());
        set("pos_sync_enabled", String.valueOf(command.posSyncEnabled()));
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
            Matcher matcher = API_KEY_PATTERN.matcher(posToken);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("API key inválida");
            }
            Store store = TenantContext.requireCurrent();
            Long companyId = Long.valueOf(matcher.group(1));
            PosConnection connection = posConnectionRepository.findByStoreId(store.getId()).orElseGet(PosConnection::new);
            connection.setStore(store);
            connection.setCompanyId(companyId);
            connection.setApiKey(command.posApiToken());
            if (notBlank(command.posWebhookSecret())) {
                connection.setWebhookSecret(command.posWebhookSecret());
            } else if (connection.getWebhookSecret() == null) {
                connection.setWebhookSecret("");
            }
            connection.setActive(true);
            if (connection.getCompanyName() == null) {
                connection.setCompanyName("UvoPOS");
            }
            if (connection.getApiUrl() == null) {
                connection.setApiUrl(command.posApiUrl() == null ? "" : command.posApiUrl());
            }
            posConnectionRepository.save(connection);
        }

        return getGeneralSettings();
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

    private void set(String key, String value) {
        Store store = TenantContext.requireCurrent();
        Setting setting = settingRepository.findByStoreIdAndSettingKey(store.getId(), key).orElseGet(Setting::new);
        setting.setStore(store);
        setting.setSettingKey(key);
        setting.setValue(value);
        settingRepository.save(setting);
    }

    // Encrypts before persisting (AES-256-GCM, see SecretCrypto) and, unlike set(), leaves the
    // stored value untouched when the incoming value is blank — the admin UI can't pre-fill this
    // field with the real secret (it's never returned by getGeneralSettings), so a save that
    // doesn't touch it must not wipe out what's already configured.
    private void setSecret(String key, String value) {
        if (!notBlank(value)) {
            return;
        }
        set(key, SecretCrypto.encrypt(value));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
