package org.uvo.uvostore.service.shipping;

// GAP-18. Un método de envío tal como lo describe la política de envíos del storefront: sin código, sin
// transportista y sin credenciales, solo lo que el comprador lee.
public record PublicShippingMethodDto(String name, String description, Integer minDeliveryDays, Integer maxDeliveryDays) {
}
