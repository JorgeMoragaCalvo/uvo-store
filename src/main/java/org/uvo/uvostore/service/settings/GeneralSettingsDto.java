package org.uvo.uvostore.service.settings;

// Mirrors Admin\Settings\SettingsForm's flat key/value settings (general/currency/shipping/
// checkout/stripe/pos/seo/social tabs) — GET response only. Secrets (Stripe secret key, POS API
// token/webhook secret) are never echoed back in plaintext — only a "*Configured" flag, same
// pattern as PaymentGatewayConfigDto.credentialsSet. See GeneralSettingsUpdateRequest for the PUT
// body, which still carries the raw secret values (write-only).
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
        boolean stripeSecretKeyConfigured,
        boolean stripeEnabled,
        String posApiUrl,
        boolean posApiTokenConfigured,
        boolean posWebhookSecretConfigured,
        boolean posSyncEnabled,
        // PROD-12, solo lectura: si esta instalación tiene la integración POS habilitada
        // (app.pos.enabled). En false, posSyncEnabled no decide nada y el panel lo dice en vez de
        // ofrecer un interruptor sin efecto. Mismo patrón que stripeSecretKeyConfigured — un dato de
        // estado que el formulario necesita y que no se escribe desde él.
        boolean posIntegrationAvailable,
        String metaTitle,
        String metaDescription,
        String metaKeywords,
        String facebookUrl,
        String instagramUrl,
        String twitterUrl
) {
}
