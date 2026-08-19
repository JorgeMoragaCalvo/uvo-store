package org.uvo.uvostore.service.customer;

public record PasswordUpdateCommand(String currentPassword, String newPassword) {
}
