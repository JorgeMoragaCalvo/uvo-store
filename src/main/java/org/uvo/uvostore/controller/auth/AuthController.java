package org.uvo.uvostore.controller.auth;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.customer.enums.AccountStatus;
import org.uvo.uvostore.entity.security.Role;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.repository.CustomerRepository;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.security.JwtService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RestController
public class AuthController {

    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(UserRepository userRepository, CustomerRepository customerRepository,
                           PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/api/admin/auth/login")
    public ResponseEntity<AuthResponse> adminLogin(@Valid @RequestBody AdminLoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("Credenciales inválidas"));

        if (!user.isActive() || !user.isAdmin() || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BadCredentialsException("Credenciales inválidas");
        }

        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        List<String> authorities = adminAuthorities(user);
        String token = jwtService.generateToken(user.getId(), user.getEmail(), "ADMIN", authorities);
        return ResponseEntity.ok(new AuthResponse(token, user.getId(), user.getName(), user.getEmail(), "ADMIN"));
    }

    @PostMapping("/api/customer/auth/login")
    public ResponseEntity<AuthResponse> customerLogin(@Valid @RequestBody CustomerLoginRequest request) {
        Customer customer = customerRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("Credenciales inválidas"));

        if (customer.getPassword() == null || customer.getAccountStatus() != AccountStatus.ACTIVE
                || !passwordEncoder.matches(request.password(), customer.getPassword())) {
            throw new BadCredentialsException("Credenciales inválidas");
        }

        String token = jwtService.generateToken(customer.getId(), customer.getEmail(), "CUSTOMER", List.of("ROLE_CUSTOMER"));
        String fullName = customer.getFirstName() + " " + customer.getLastName();
        return ResponseEntity.ok(new AuthResponse(token, customer.getId(), fullName, customer.getEmail(), "CUSTOMER"));
    }

    @PostMapping("/api/customer/auth/register")
    public ResponseEntity<AuthResponse> customerRegister(@Valid @RequestBody CustomerRegisterRequest request) {
        if (customerRepository.existsByEmail(request.email())) {
            throw new IllegalStateException("El correo ya está registrado");
        }

        Customer customer = new Customer();
        customer.setEmail(request.email());
        customer.setPassword(passwordEncoder.encode(request.password()));
        customer.setFirstName(request.firstName());
        customer.setLastName(request.lastName());
        customer.setPhone(request.phone());
        customer.setAccountStatus(AccountStatus.ACTIVE);
        Customer saved = customerRepository.save(customer);

        String token = jwtService.generateToken(saved.getId(), saved.getEmail(), "CUSTOMER", List.of("ROLE_CUSTOMER"));
        String fullName = saved.getFirstName() + " " + saved.getLastName();
        return ResponseEntity.ok(new AuthResponse(token, saved.getId(), fullName, saved.getEmail(), "CUSTOMER"));
    }

    private List<String> adminAuthorities(User user) {
        List<String> authorities = new ArrayList<>();
        authorities.add("ROLE_ADMIN");
        for (Role role : user.getRoles()) {
            role.getPermissions().forEach(permission -> authorities.add(permission.getName()));
        }
        return authorities;
    }
}
