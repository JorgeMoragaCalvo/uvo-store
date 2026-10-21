package org.uvo.uvostore.service.catalog;

import java.util.List;

/**
 * POS-2. Qué campos de un producto gestiona UvoPOS, con los nombres del formulario del panel
 * ({@link PosOwnedField#formField}). Vacío si el producto no está sincronizado.
 *
 * <p>Va aparte de {@code ProductDto} a propósito: ese DTO también lo sirve la API pública del storefront, y
 * esto solo le interesa al panel.
 */
public record PosOwnershipDto(List<String> lockedFields) {
}
