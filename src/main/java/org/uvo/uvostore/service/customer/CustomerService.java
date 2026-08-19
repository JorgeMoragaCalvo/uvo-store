package org.uvo.uvostore.service.customer;

import org.uvo.uvostore.entity.customer.Customer;

public interface CustomerService {
    CustomerDto getProfile(Long customerId);
    CustomerDto updateProfile(Long customerId, ProfileUpdateCommand command);
    void updatePassword(Long customerId, PasswordUpdateCommand command);
    // Ports the guest-checkout find-or-create used by CheckoutController::store() — a checkout
    // with no logged-in customer creates/reuses a Customer row by email with accountStatus=GUEST.
    Customer findOrCreateGuest(String email, String firstName, String lastName, String phone);

    // Ports CheckoutController::store()'s post-firstOrCreate step: a fresh guest with no
    // invitation yet gets one generated so a follow-up email can invite them to set a password.
    Customer markInvitedIfGuest(Customer customer);
}
