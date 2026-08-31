package org.uvo.uvostore.service.settings;

// PUT body for /api/admin/settings/general. Carries raw secret values (write-only — never
// returned, see GeneralSettingsDto). A blank/null secret means "leave the stored value
// unchanged" — the frontend can't pre-fill these fields with the real value anymore, so a submit
// that doesn't touch them must not wipe out what's already saved.
public record GeneralSettingsUpdateRequest(
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
