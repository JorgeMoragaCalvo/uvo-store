package org.uvo.uvostore.service.customer;

import java.util.List;

public interface CustomerAddressService {
    List<ShippingAddressDto> listAddresses(Long customerId);
    ShippingAddressDto createAddress(Long customerId, ShippingAddressCommand command);
    ShippingAddressDto updateAddress(Long customerId, Long addressId, ShippingAddressCommand command);
    void deleteAddress(Long customerId, Long addressId);
    ShippingAddressDto setDefaultAddress(Long customerId, Long addressId);
}
