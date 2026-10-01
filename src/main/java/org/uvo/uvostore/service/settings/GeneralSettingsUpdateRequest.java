package org.uvo.uvostore.service.settings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// PUT body for /api/admin/settings/general. Carries raw secret values (write-only — never
// returned, see GeneralSettingsDto). A blank/null secret means "leave the stored value
// unchanged" — the frontend can't pre-fill these fields with the real value anymore, so a submit
// that doesn't touch them must not wipe out what's already saved.
//
// F17: los cinco campos que participan en dinero siguen llegando como String a propósito —el DTO de
// lectura los devuelve como texto y el formulario los reenvía tal cual—, pero ya no llegan sin
// comprobar. @NotBlank es solo la primera capa: el rango, el catálogo de divisas y el recorte están en
// SettingValues, que es además quien los lee al cotizar, para que escritura y lectura no puedan volver
// a discrepar.
public record GeneralSettingsUpdateRequest(
        String storeName,
        String storeEmail,
        String storePhone,
        String adminEmail,
        @NotBlank(message = "La divisa es obligatoria") String currency,
        @NotBlank(message = "El símbolo de la divisa es obligatorio")
        @Size(max = 8, message = "El símbolo de la divisa no puede tener más de 8 caracteres")
        String currencySymbol,
        @NotBlank(message = "La tasa de impuesto es obligatoria") String taxRate,
        boolean pricesIncludeTax,
        boolean shippingEnabled,
        @NotBlank(message = "El costo de envío por defecto es obligatorio") String defaultShippingCost,
        boolean freeShippingEnabled,
        @NotBlank(message = "El monto mínimo para envío gratis es obligatorio") String freeShippingThreshold,
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
