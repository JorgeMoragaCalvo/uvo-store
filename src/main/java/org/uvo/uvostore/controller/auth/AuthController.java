package org.uvo.uvostore.controller.auth;

import org.uvo.uvostore.service.BusinessException;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.customer.enums.AccountStatus;
import org.uvo.uvostore.entity.security.Role;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.CustomerRepository;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.security.AccountAttemptThrottle;
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
    private final AccountAttemptThrottle accountThrottle;
    private final String frontendUrl;
    // F24: días y no una hora como el reset de contraseña — el reset lo pide el usuario y lo usa en el
    // momento; la invitación le llega sin haberla pedido, dentro del correo del pedido.
    private final Duration invitationTtl;

    public AuthController(UserRepository userRepository, CustomerRepository customerRepository,
                           PasswordEncoder passwordEncoder, JwtService jwtService, EmailService emailService,
                           TokenVersionService tokenVersionService, AccountAttemptThrottle accountThrottle,
                           @Value("${app.frontend-url}") String frontendUrl,
                           @Value("${app.customer-invitation.ttl-days:30}") int invitationTtlDays) {
        this.userRepository = userRepository;
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.emailService = emailService;
        this.tokenVersionService = tokenVersionService;
        this.accountThrottle = accountThrottle;
        this.frontendUrl = frontendUrl;
        this.invitationTtl = Duration.ofDays(invitationTtlDays);
    }

    @PostMapping("/api/admin/auth/login")
    @Transactional // also persists lastLoginAt; adminAuthorities() below walks the lazy User.roles/Role.permissions collections
    public ResponseEntity<AuthResponse> adminLogin(@Valid @RequestBody AdminLoginRequest request) {
        Store store = TenantContext.requireCurrent();
        // F18: el presupuesto de ESTA cuenta, antes de mirar nada. El límite por IP de RateLimitFilter no
        // ve al atacante que reparte los intentos entre muchas direcciones. Se comprueba sin consumir, y
        // más abajo se cuenta el fallo: lo que gasta presupuesto es equivocarse, no intentarlo.
        //
        // Y se cuenta exista o no la cuenta, a propósito: un 429 que solo apareciera para los correos
        // reales diría qué correos son reales.
        accountThrottle.check(store.getId(), AccountAttemptThrottle.Flow.ADMIN_LOGIN, request.email());

        User user = userRepository.findByStoreIdAndEmail(store.getId(), request.email()).orElse(null);
        if (user == null || !user.isActive() || !user.isAdmin()
                || !passwordEncoder.matches(request.password(), user.getPassword())) {
            accountThrottle.recordFailure(store.getId(), AccountAttemptThrottle.Flow.ADMIN_LOGIN, request.email());
            throw new BadCredentialsException("Credenciales inválidas");
        }
        accountThrottle.clear(store.getId(), AccountAttemptThrottle.Flow.ADMIN_LOGIN, request.email());

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
        accountThrottle.check(store.getId(), AccountAttemptThrottle.Flow.CUSTOMER_LOGIN, request.email());

        Customer customer = customerRepository.findByStoreIdAndEmail(store.getId(), request.email()).orElse(null);
        if (customer == null || customer.getPassword() == null || customer.getAccountStatus() != AccountStatus.ACTIVE
                || !passwordEncoder.matches(request.password(), customer.getPassword())) {
            accountThrottle.recordFailure(store.getId(), AccountAttemptThrottle.Flow.CUSTOMER_LOGIN, request.email());
            throw new BadCredentialsException("Credenciales inválidas");
        }
        accountThrottle.clear(store.getId(), AccountAttemptThrottle.Flow.CUSTOMER_LOGIN, request.email());

        String token = jwtService.generateToken(customer.getId(), customer.getEmail(), "CUSTOMER", store.getId(),
                List.of("ROLE_CUSTOMER"), customer.getTokenVersion());
        String fullName = customer.getFirstName() + " " + customer.getLastName();
        return ResponseEntity.ok(new AuthResponse(token, customer.getId(), fullName, customer.getEmail(), "CUSTOMER", List.of()));
    }

    @PostMapping("/api/customer/auth/register")
    public ResponseEntity<AuthResponse> customerRegister(@Valid @RequestBody CustomerRegisterRequest request) {
        Store store = TenantContext.requireCurrent();
        if (customerRepository.existsByStoreIdAndEmail(store.getId(), request.email())) {
            throw new BusinessException("El correo ya está registrado");
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


    /**
     * F24. El invitado convierte su correo en cuenta.
     *
     * <p>Sin esto, comprar como invitado dejaba el correo <b>inservible</b> en esa tienda: el checkout
     * creaba la fila con estado {@code INVITED} y un token que nadie leía, {@code customerRegister} lo
     * rechazaba por existir y {@code customerLogin} exige {@code ACTIVE} con contraseña. No había forma de
     * salir de ahí por la API.
     *
     * <p>El token llega por correo, y eso es justamente lo que prueba que quien activa la cuenta es el
     * dueño del buzón. Por eso no se resolvió dejando que el registro reclame la fila sin contraseña:
     * nadie verifica el correo al comprar como invitado, así que cualquiera que lo conociera podría
     * adelantarse al dueño y quedarse con sus datos.
     *
     * <p>Se busca <b>por token</b> y no por tienda porque el token es único y de un solo uso; la tienda
     * sale de la fila encontrada, y se comprueba que coincide con la del host para que una invitación no
     * se pueda canjear desde el dominio de otra tienda.
     */
    @PostMapping("/api/customer/auth/accept-invitation")
    @Transactional
    public ResponseEntity<AuthResponse> acceptInvitation(@Valid @RequestBody AcceptInvitationRequest request) {
        Store store = TenantContext.requireCurrent();
        Customer customer = customerRepository.findByInvitationToken(request.token())
                .filter(candidate -> candidate.getStore().getId().equals(store.getId()))
                .filter(candidate -> candidate.getInvitationSentAt() != null
                        && candidate.getInvitationSentAt().plus(invitationTtl).isAfter(Instant.now()))
                // Un token inexistente, caducado, de otra tienda o ya usado dicen lo mismo: no hay por qué
                // contarle a quien prueba tokens en qué se equivocó.
                .orElseThrow(() -> new BusinessException("La invitación no es válida o ha expirado"));

        customer.setPassword(passwordEncoder.encode(request.password()));
        customer.setAccountStatus(AccountStatus.ACTIVE);
        // De un solo uso: el token se consume aquí, así que el mismo enlace no sirve dos veces.
        customer.setInvitationToken(null);
        Customer saved = customerRepository.save(customer);

        // Se devuelve la sesión ya iniciada, como en el registro: el invitado acaba de demostrar que
        // controla el correo y que sabe la contraseña que él mismo acaba de fijar.
        String token = jwtService.generateToken(saved.getId(), saved.getEmail(), "CUSTOMER", store.getId(),
                List.of("ROLE_CUSTOMER"), saved.getTokenVersion());
        String fullName = saved.getFirstName() + " " + saved.getLastName();
        return ResponseEntity.ok(new AuthResponse(token, saved.getId(), fullName, saved.getEmail(), "CUSTOMER", List.of()));
    }

    @PostMapping("/api/admin/auth/forgot-password")
    @Transactional
    public ResponseEntity<Void> adminForgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        Store store = TenantContext.requireCurrent();
        // F18: aquí cada llamada cuenta, no solo las que fallan, porque el costo de este endpoint es el
        // correo que sale. Y cuenta antes de buscar la cuenta: si solo contara las que existen, el 429
        // acabaría diciendo exactamente lo que el 200 de abajo se cuida de no decir.
        accountThrottle.check(store.getId(), AccountAttemptThrottle.Flow.ADMIN_FORGOT_PASSWORD, request.email());
        accountThrottle.recordFailure(store.getId(), AccountAttemptThrottle.Flow.ADMIN_FORGOT_PASSWORD, request.email());

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
