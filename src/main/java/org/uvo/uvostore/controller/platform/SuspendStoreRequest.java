package org.uvo.uvostore.controller.platform;

import jakarta.validation.constraints.NotNull;
import org.uvo.uvostore.service.platform.StoreStatusReason;

/** PROD-23. El motivo es obligatorio y de una lista cerrada: va a la auditoría, que no guarda texto libre. */
public record SuspendStoreRequest(@NotNull StoreStatusReason reason) {
}
