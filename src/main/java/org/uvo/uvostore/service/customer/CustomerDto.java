package org.uvo.uvostore.service.customer;

public record CustomerDto(Long id, String email, String firstName, String lastName, String phone, String accountStatus) {
}
