package org.uvo.uvostore.service.customer;

public record ProfileUpdateCommand(String firstName, String lastName, String phone, String email) {
}
