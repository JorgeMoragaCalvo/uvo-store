package org.uvo.uvostore.service.order;

public interface CheckoutService {
    OrderConfirmation checkout(CheckoutCommand command);
    CheckoutConfigDto getConfig();
}
