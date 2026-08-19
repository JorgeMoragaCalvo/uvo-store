package org.uvo.uvostore.controller.admin.order;

import jakarta.validation.constraints.NotBlank;

public record StatusRequest(@NotBlank String status) {
}
