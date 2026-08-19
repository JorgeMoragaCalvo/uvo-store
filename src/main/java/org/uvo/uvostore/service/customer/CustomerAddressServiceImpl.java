package org.uvo.uvostore.service.customer;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.customer.ShippingAddress;
import org.uvo.uvostore.repository.CustomerRepository;
import org.uvo.uvostore.repository.ShippingAddressRepository;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class CustomerAddressServiceImpl implements CustomerAddressService {

    private final ShippingAddressRepository shippingAddressRepository;
    private final CustomerRepository customerRepository;

    public CustomerAddressServiceImpl(ShippingAddressRepository shippingAddressRepository, CustomerRepository customerRepository) {
        this.shippingAddressRepository = shippingAddressRepository;
        this.customerRepository = customerRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShippingAddressDto> listAddresses(Long customerId) {
        return shippingAddressRepository.findByCustomerId(customerId).stream()
                .sorted(Comparator.comparing(ShippingAddress::isDefault).reversed()
                        .thenComparing(Comparator.comparing(ShippingAddress::getCreatedAt).reversed()))
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional
    public ShippingAddressDto createAddress(Long customerId, ShippingAddressCommand command) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new NoSuchElementException("Customer " + customerId + " not found"));

        ShippingAddress address = new ShippingAddress();
        address.setCustomer(customer);
        applyCommonFields(address, command);

        if (command.isDefault()) {
            unsetOtherDefaults(customerId, null);
        }

        return toDto(shippingAddressRepository.save(address));
    }

    @Override
    @Transactional
    public ShippingAddressDto updateAddress(Long customerId, Long addressId, ShippingAddressCommand command) {
        ShippingAddress address = findOwnedOrThrow(customerId, addressId);
        applyCommonFields(address, command);

        if (command.isDefault()) {
            unsetOtherDefaults(customerId, addressId);
        }

        return toDto(shippingAddressRepository.save(address));
    }

    @Override
    @Transactional
    public void deleteAddress(Long customerId, Long addressId) {
        ShippingAddress address = findOwnedOrThrow(customerId, addressId);
        shippingAddressRepository.delete(address);
    }

    @Override
    @Transactional
    public ShippingAddressDto setDefaultAddress(Long customerId, Long addressId) {
        ShippingAddress address = findOwnedOrThrow(customerId, addressId);
        unsetOtherDefaults(customerId, addressId);
        address.setDefault(true);
        return toDto(shippingAddressRepository.save(address));
    }

    // Ports ShippingAddress::booted()'s saving-event auto-default: when an address is (or becomes)
    // the default, every other address of the same customer loses is_default.
    private void unsetOtherDefaults(Long customerId, Long exceptAddressId) {
        for (ShippingAddress other : shippingAddressRepository.findByCustomerId(customerId)) {
            if (other.isDefault() && !other.getId().equals(exceptAddressId)) {
                other.setDefault(false);
                shippingAddressRepository.save(other);
            }
        }
    }

    private ShippingAddress findOwnedOrThrow(Long customerId, Long addressId) {
        ShippingAddress address = shippingAddressRepository.findById(addressId)
                .orElseThrow(() -> new NoSuchElementException("Address " + addressId + " not found"));
        if (!address.getCustomer().getId().equals(customerId)) {
            throw new AccessDeniedException("La dirección no pertenece a este cliente");
        }
        return address;
    }

    private void applyCommonFields(ShippingAddress address, ShippingAddressCommand command) {
        address.setFirstName(command.firstName());
        address.setLastName(command.lastName());
        address.setCompany(command.company());
        address.setAddressLine1(command.addressLine1());
        address.setAddressLine2(command.addressLine2());
        address.setCity(command.city());
        address.setState(command.state());
        address.setPostalCode(command.postalCode());
        address.setCountry(command.country() == null || command.country().isBlank() ? "CL" : command.country());
        address.setPhone(command.phone());
    }

    private ShippingAddressDto toDto(ShippingAddress address) {
        return new ShippingAddressDto(
                address.getId(), address.getFirstName(), address.getLastName(), address.getCompany(),
                address.getAddressLine1(), address.getAddressLine2(), address.getCity(), address.getState(),
                address.getPostalCode(), address.getCountry(), address.getPhone(), address.isDefault()
        );
    }
}
