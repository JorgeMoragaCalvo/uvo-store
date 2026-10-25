package org.uvo.uvostore.controller.platform;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.uvo.uvostore.service.platform.StoreStatusReason;

/**
 * PROD-23. Cerrar es irreversible, así que se pide el nick de la tienda escrito: un id equivocado en la URL no cierra
 * otra tienda.
 */
public record CloseStoreRequest(@NotBlank String confirmSlug, @NotNull StoreStatusReason reason) {
}
