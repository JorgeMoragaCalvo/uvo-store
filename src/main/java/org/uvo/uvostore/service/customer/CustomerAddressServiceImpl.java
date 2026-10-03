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
        // F23: la tienda, que nadie asignaba. `shipping_addresses.store_id` es NOT NULL desde V8 (el
        // multi-tenant) y este servicio es el ÚNICO que inserta en esa tabla, así que crear una dirección
        // terminaba siempre en 500 por violación de la restricción: el endpoint no había funcionado nunca.
        // Lo descubrió el test de esta misma corrección — el hallazgo da por hecho que crear "responde
        // isDefault=false", y en realidad no responde.
        //
        // Se toma la del cliente y no TenantContext para que la dirección no pueda acabar en una tienda
        // distinta de la de su dueño, que es la misma regla que ya usan las consultas por tienda.
        address.setStore(customer.getStore());
        applyCommonFields(address, command);

        if (command.isDefault()) {
            // En crear no hay nada que excluir: la entidad todavía no tiene id.
            unsetOtherDefaults(customerId, null);
        }
        applyDefaultFlag(address, command);

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
        applyDefaultFlag(address, command);

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

    /**
     * F23. La bandera que nunca se aplicaba.
     *
     * <p>{@code applyCommonFields} copiaba los once campos de texto y se dejaba este, mientras el
     * {@code if (command.isDefault())} que desmarca <b>las demás</b> sí estaba en crear y en editar. O sea
     * que se ejecutaba la mitad destructiva de la operación y no la constructiva: pedir "que esta sea la
     * predeterminada" <b>borraba la que había</b> y no marcaba la nueva, dejando al cliente sin ninguna.
     * Y un {@code false} sobre la predeterminada se ignoraba, así que la bandera solo se podía encender
     * por el endpoint aparte y nunca apagar.
     *
     * <p>La causa exacta: {@link #unsetOtherDefaults} porta el hook {@code saving} de
     * {@code ShippingAddress::booted()} del Laravel original, que hace <b>solo</b> el «desmarca las demás»
     * — y allí funcionaba porque la asignación en masa ya había puesto {@code is_default} desde la
     * petición. Al portarlo se copió el efecto y se perdió la causa.
     *
     * <p>Va fuera de {@code applyCommonFields} y <b>después</b> de desmarcar las otras, para que se lea en
     * el mismo sitio que la operación con la que tiene que coordinarse en vez de esconderse entre los
     * campos de texto.
     *
     * <p>Se honra también el {@code false}: si la predeterminada deja de serlo, el cliente se queda sin
     * ninguna. La invariante es «como máximo una», no «al menos una» — y desoír lo que el cliente manda es
     * precisamente cómo se llegó a este fallo.
     */
    private void applyDefaultFlag(ShippingAddress address, ShippingAddressCommand command) {
        address.setDefault(command.isDefault());
    }

    // Ports ShippingAddress::booted()'s saving-event auto-default: when an address is (or becomes)
    // the default, every other address of the same customer loses is_default.
    //
    // F23: por sí solo esto no basta — hace falta que alguien marque la nueva, que es applyDefaultFlag.
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
