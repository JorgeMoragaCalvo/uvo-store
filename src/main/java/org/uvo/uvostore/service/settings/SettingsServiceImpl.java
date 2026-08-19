package org.uvo.uvostore.service.settings;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.settings.Setting;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.SettingRepository;

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
        return new GeneralSettingsDto(
                get("store_name", "UvoStore"), get("store_email", ""), get("store_phone", ""), get("admin_email", ""),
                get("currency", "CLP"), get("currency_symbol", "$"), get("tax_rate", "19"), getBool("prices_include_tax", false),
                getBool("shipping_enabled", true), get("default_shipping_cost", "0"), getBool("free_shipping_enabled", false),
                get("free_shipping_threshold", "0"), getBool("allow_guest_checkout", true), getBool("require_phone", false),
                getBool("require_company", false), get("stripe_public_key", ""), get("stripe_secret_key", ""),
                getBool("stripe_enabled", false), get("pos_api_url", ""), get("pos_api_token", ""), get("pos_webhook_secret", ""),
                getBool("pos_sync_enabled", false), get("meta_title", ""), get("meta_description", ""), get("meta_keywords", ""),
                get("facebook_url", ""), get("instagram_url", ""), get("twitter_url", "")
        );
    }

    @Override
    @Transactional
    public GeneralSettingsDto updateGeneralSettings(GeneralSettingsDto command) {
        Matcher matcher = API_KEY_PATTERN.matcher(command.posApiToken() == null ? "" : command.posApiToken().trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("API key inválida");
        }

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
        set("stripe_secret_key", command.stripeSecretKey());
        set("stripe_enabled", String.valueOf(command.stripeEnabled()));
        set("pos_api_url", command.posApiUrl());
        set("pos_api_token", command.posApiToken());
        set("pos_webhook_secret", command.posWebhookSecret());
        set("pos_sync_enabled", String.valueOf(command.posSyncEnabled()));
        set("meta_title", command.metaTitle());
        set("meta_description", command.metaDescription());
        set("meta_keywords", command.metaKeywords());
        set("facebook_url", command.facebookUrl());
        set("instagram_url", command.instagramUrl());
        set("twitter_url", command.twitterUrl());

        Long companyId = Long.valueOf(matcher.group(1));
        PosConnection connection = posConnectionRepository.findById(1L).orElseGet(PosConnection::new);
        connection.setCompanyId(companyId);
        connection.setApiKey(command.posApiToken());
        connection.setWebhookSecret(command.posWebhookSecret());
        connection.setActive(true);
        if (connection.getCompanyName() == null) {
            connection.setCompanyName("UvoPOS");
        }
        if (connection.getApiUrl() == null) {
            connection.setApiUrl(command.posApiUrl() == null ? "" : command.posApiUrl());
        }
        posConnectionRepository.save(connection);

        return getGeneralSettings();
    }

    private String get(String key, String fallback) {
        return settingRepository.findBySettingKey(key).map(Setting::getValue).orElse(fallback);
    }

    private boolean getBool(String key, boolean fallback) {
        return settingRepository.findBySettingKey(key).map(s -> Boolean.parseBoolean(s.getValue())).orElse(fallback);
    }

    private void set(String key, String value) {
        Setting setting = settingRepository.findBySettingKey(key).orElseGet(Setting::new);
        setting.setSettingKey(key);
        setting.setValue(value);
        settingRepository.save(setting);
    }
}
