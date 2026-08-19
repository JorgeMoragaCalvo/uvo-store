package org.uvo.uvostore.service.settings;

// Mirrors Admin\Settings\SettingsForm's flat key/value settings (general/currency/shipping/
// checkout/stripe/pos/seo/social tabs) — used both as the GET response and the PUT request body.
public record GeneralSettingsDto(
        String storeName,
        String storeEmail,
        String storePhone,
        String adminEmail,
        String currency,
        String currencySymbol,
        String taxRate,
        boolean pricesIncludeTax,
        boolean shippingEnabled,
        String defaultShippingCost,
        boolean freeShippingEnabled,
        String freeShippingThreshold,
        boolean allowGuestCheckout,
        boolean requirePhone,
        boolean requireCompany,
        String stripePublicKey,
        String stripeSecretKey,
        boolean stripeEnabled,
        String posApiUrl,
        String posApiToken,
        String posWebhookSecret,
        boolean posSyncEnabled,
        String metaTitle,
        String metaDescription,
        String metaKeywords,
        String facebookUrl,
        String instagramUrl,
        String twitterUrl
) {
}
