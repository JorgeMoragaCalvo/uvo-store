package org.uvo.uvostore.service.customer;

import org.uvo.uvostore.service.BusinessException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.customer.enums.AccountStatus;
import org.uvo.uvostore.repository.CustomerRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.security.TokenVersionService;

import java.util.NoSuchElementException;

@Service
public class CustomerServiceImpl implements CustomerService {

    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenVersionService tokenVersionService;

    public CustomerServiceImpl(CustomerRepository customerRepository, PasswordEncoder passwordEncoder,
                                TokenVersionService tokenVersionService) {
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenVersionService = tokenVersionService;
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

        customerRepository.findByStoreIdAndEmail(TenantContext.requireStoreId(), command.email())
                .filter(existing -> !existing.getId().equals(customerId))
                .ifPresent(existing -> {
                    throw new BusinessException("El correo ya está en uso por otra cuenta");
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

        // F22. La contraseña nueva mata las sesiones viejas, que es justo lo que alguien espera al
        // cambiarla porque sospecha que le robaron la suya. Sin esto el token robado seguía entrando
        // hasta caducar solo (24 h), así que el remedio del usuario no remediaba nada.
        //
        // Se llama al método que ya existía para esto —y que no tenía ningún llamador— en vez de copiar
        // por tercera vez el setTokenVersion(+1) + evict que el lado admin hace a mano
        // (AuthController.adminResetPassword, UserServiceImpl.revokeTokens). Un método de revocación
        // muerto conviviendo con copias manuales es como esta clase de fallo vuelve a aparecer.
        //
        // Va DESPUÉS de validar la contraseña actual a propósito: si fuera antes, cualquiera con el
        // token podría echar al dueño de su propia sesión probando contraseñas al azar.
        //
        // El endpoint sigue respondiendo 204 sin token nuevo, así que quien cambia su contraseña
        // también cierra su propia sesión — igual que el reset de admin. Cuando exista superficie de
        // cliente en la SPA, lo amable es emitir allí un token nuevo (después del incremento).
        tokenVersionService.revokeCustomerTokens(customerId);
    }

    @Override
    @Transactional
    public Customer findOrCreateGuest(String email, String firstName, String lastName, String phone) {
        Long storeId = TenantContext.requireStoreId();
        return customerRepository.findByStoreIdAndEmail(storeId, email).orElseGet(() -> {
            Customer customer = new Customer();
            customer.setStore(TenantContext.requireCurrent());
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
