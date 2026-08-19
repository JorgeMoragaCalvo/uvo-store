package org.uvo.uvostore.service.customer;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.customer.enums.AccountStatus;
import org.uvo.uvostore.repository.CustomerRepository;

import java.util.NoSuchElementException;

@Service
public class CustomerServiceImpl implements CustomerService {

    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;

    public CustomerServiceImpl(CustomerRepository customerRepository, PasswordEncoder passwordEncoder) {
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerDto getProfile(Long customerId) {
        return toDto(findOrThrow(customerId));
    }

    @Override
    @Transactional
    public CustomerDto updateProfile(Long customerId, ProfileUpdateCommand command) {
        Customer customer = findOrThrow(customerId);

        customerRepository.findByEmail(command.email())
                .filter(existing -> !existing.getId().equals(customerId))
                .ifPresent(existing -> {
                    throw new IllegalStateException("El correo ya está en uso por otra cuenta");
                });

        customer.setFirstName(command.firstName());
        customer.setLastName(command.lastName());
        customer.setPhone(command.phone());
        customer.setEmail(command.email());
        return toDto(customerRepository.save(customer));
    }

    @Override
    @Transactional
    public void updatePassword(Long customerId, PasswordUpdateCommand command) {
        Customer customer = findOrThrow(customerId);

        if (customer.getPassword() == null || !passwordEncoder.matches(command.currentPassword(), customer.getPassword())) {
            throw new BadCredentialsException("La contraseña actual es incorrecta");
        }

        customer.setPassword(passwordEncoder.encode(command.newPassword()));
        customerRepository.save(customer);
    }

    @Override
    @Transactional
    public Customer findOrCreateGuest(String email, String firstName, String lastName, String phone) {
        return customerRepository.findByEmail(email).orElseGet(() -> {
            Customer customer = new Customer();
            customer.setEmail(email);
            customer.setFirstName(firstName);
            customer.setLastName(lastName);
            customer.setPhone(phone);
            customer.setAccountStatus(AccountStatus.GUEST);
            return customerRepository.save(customer);
        });
    }

    @Override
    @Transactional
    public Customer markInvitedIfGuest(Customer customer) {
        if (customer.getAccountStatus() == AccountStatus.GUEST && customer.getInvitationToken() == null) {
            customer.setInvitationToken(java.util.UUID.randomUUID().toString().replace("-", "") + java.util.UUID.randomUUID().toString().replace("-", ""));
            customer.setInvitationSentAt(java.time.Instant.now());
            customer.setAccountStatus(AccountStatus.INVITED);
            return customerRepository.save(customer);
        }
        return customer;
    }

    private Customer findOrThrow(Long customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new NoSuchElementException("Customer " + customerId + " not found"));
    }

    private CustomerDto toDto(Customer customer) {
        return new CustomerDto(customer.getId(), customer.getEmail(), customer.getFirstName(),
                customer.getLastName(), customer.getPhone(), customer.getAccountStatus().name());
    }
}
