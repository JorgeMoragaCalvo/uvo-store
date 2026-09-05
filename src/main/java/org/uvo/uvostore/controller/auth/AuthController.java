package org.uvo.uvostore.controller.auth;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.customer.enums.AccountStatus;
import org.uvo.uvostore.entity.security.Role;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.CustomerRepository;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.security.JwtService;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.security.TokenVersionService;
import org.uvo.uvostore.service.notification.EmailService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Autenticación", description = "Login/registro de admin y cliente, recuperación de contraseña — todo público, emite el JWT bearer usado por las superficies admin y cliente")
@RestController
public class AuthController {

    // Reset links expire quickly since, unlike the invitation token, this one can be requested
    // repeatedly by anyone who knows an email address.
    private static final Duration PASSWORD_RESET_TTL = Duration.ofHours(1);

    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailService emailService;
    private final TokenVersionService tokenVersionService;
    private final String frontendUrl;

    public AuthController(UserRepository userRepository, CustomerRepository customerRepository,
                           PasswordEncoder passwordEncoder, JwtService jwtService, EmailService emailService,
                           TokenVersionService tokenVersionService,
                           @Value("${app.frontend-url}") String frontendUrl) {
        this.userRepository = userRepository;
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.emailService = emailService;
        this.tokenVersionService = tokenVersionService;
        this.frontendUrl = frontendUrl;
    }

    @PostMapping("/api/admin/auth/login")
    @Transactional // also persists lastLoginAt; adminAuthorities() below walks the lazy User.roles/Role.permissions collections
    public ResponseEntity<AuthResponse> adminLogin(@Valid @RequestBody AdminLoginRequest request) {
        Store store = TenantContext.requireCurrent();
        User user = userRepository.findByStoreIdAndEmail(store.getId(), request.email())
                .orElseThrow(() -> new BadCredentialsException("Credenciales inválidas"));

        if (!user.isActive() || !user.isAdmin() || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BadCredentialsException("Credenciales inválidas");
        }

        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        List<String> authorities = adminAuthorities(user);
        String token = jwtService.generateToken(user.getId(), user.getEmail(), "ADMIN", store.getId(),
                authorities, user.getTokenVersion());
        // Same list the token carries, minus the ROLE_ADMIN marker: the panel needs the
        // permission names to decide which sections to show.
        List<String> permissions = authorities.stream().filter(a -> !a.startsWith("ROLE_")).toList();
        return ResponseEntity.ok(new AuthResponse(token, user.getId(), user.getName(), user.getEmail(), "ADMIN", permissions));
    }

    @PostMapping("/api/customer/auth/login")
    public ResponseEntity<AuthResponse> customerLogin(@Valid @RequestBody CustomerLoginRequest request) {
        Store store = TenantContext.requireCurrent();
        Customer customer = customerRepository.findByStoreIdAndEmail(store.getId(), request.email())
                .orElseThrow(() -> new BadCredentialsException("Credenciales inválidas"));

        if (customer.getPassword() == null || customer.getAccountStatus() != AccountStatus.ACTIVE
                || !passwordEncoder.matches(request.password(), customer.getPassword())) {
            throw new BadCredentialsException("Credenciales inválidas");
        }

        String token = jwtService.generateToken(customer.getId(), customer.getEmail(), "CUSTOMER", store.getId(),
                List.of("ROLE_CUSTOMER"), customer.getTokenVersion());
        String fullName = customer.getFirstName() + " " + customer.getLastName();
        return ResponseEntity.ok(new AuthResponse(token, customer.getId(), fullName, customer.getEmail(), "CUSTOMER", List.of()));
    }

    @PostMapping("/api/customer/auth/register")
    public ResponseEntity<AuthResponse> customerRegister(@Valid @RequestBody CustomerRegisterRequest request) {
        Store store = TenantContext.requireCurrent();
        if (customerRepository.existsByStoreIdAndEmail(store.getId(), request.email())) {
            throw new IllegalStateException("El correo ya está registrado");
        }

        Customer customer = new Customer();
        customer.setStore(store);
        customer.setEmail(request.email());
        customer.setPassword(passwordEncoder.encode(request.password()));
        customer.setFirstName(request.firstName());
        customer.setLastName(request.lastName());
        customer.setPhone(request.phone());
        customer.setAccountStatus(AccountStatus.ACTIVE);
        Customer saved = customerRepository.save(customer);

        String token = jwtService.generateToken(saved.getId(), saved.getEmail(), "CUSTOMER", store.getId(),
                List.of("ROLE_CUSTOMER"), saved.getTokenVersion());
        String fullName = saved.getFirstName() + " " + saved.getLastName();
        return ResponseEntity.ok(new AuthResponse(token, saved.getId(), fullName, saved.getEmail(), "CUSTOMER", List.of()));
    }


    @PostMapping("/api/admin/auth/forgot-password")
    @Transactional
    public ResponseEntity<Void> adminForgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        Store store = TenantContext.requireCurrent();
        // Always respond 200 regardless of whether the email matches an account — don't leak
        // which admin emails exist on this store.
        userRepository.findByStoreIdAndEmail(store.getId(), request.email()).ifPresent(user -> {
            String token = UUID.randomUUID().toString();
            user.setPasswordResetToken(token);
            user.setPasswordResetExpiresAt(Instant.now().plus(PASSWORD_RESET_TTL));
            userRepository.save(user);

            String link = frontendUrl + "/admin/reset-password?token=" + token;
            emailService.send(user.getEmail(), "Recupera tu contraseña",
                    "Recibimos una solicitud para restablecer tu contraseña.\n\n"
                            + "Ingresa al siguiente enlace para elegir una nueva (válido por 1 hora):\n" + link
                            + "\n\nSi no fuiste tú, puedes ignorar este correo.");
        });
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/admin/auth/reset-password")
    @Transactional
    public ResponseEntity<Void> adminResetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        User user = userRepository.findByPasswordResetToken(request.token())
                .filter(candidate -> candidate.getPasswordResetExpiresAt() != null
                        && candidate.getPasswordResetExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new BadCredentialsException("El enlace no es válido o ha expirado"));

        user.setPassword(passwordEncoder.encode(request.password()));
        user.setPasswordResetToken(null);
        user.setPasswordResetExpiresAt(null);
        // A5: a password reset is exactly the case where old sessions must die — whoever prompted
        // the reset may be locking someone else out of the account.
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
        tokenVersionService.evict(TokenVersionService.ADMIN, user.getId());
        return ResponseEntity.ok().build();
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
