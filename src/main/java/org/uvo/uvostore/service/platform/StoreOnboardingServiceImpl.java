package org.uvo.uvostore.service.platform;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.audit.enums.AuditAction;
import org.uvo.uvostore.entity.audit.enums.AuditTargetType;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.security.Role;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.settings.Setting;
import org.uvo.uvostore.entity.settings.StoreSettings;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.entity.tenant.enums.StoreStatus;
import org.uvo.uvostore.repository.PermissionRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.RoleRepository;
import org.uvo.uvostore.repository.SettingRepository;
import org.uvo.uvostore.repository.StoreRepository;
import org.uvo.uvostore.repository.StoreSettingsRepository;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.audit.AuditDiff;
import org.uvo.uvostore.service.audit.AuditEntry;
import org.uvo.uvostore.service.audit.AuditTrail;
import org.uvo.uvostore.service.pos.PosApiKey;
import org.uvo.uvostore.service.pos.PosIntegrationPolicy;
import org.uvo.uvostore.service.security.AdminInvitationMailer;
import org.uvo.uvostore.service.url.StorePublicUrlResolver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

// Store onboarding is deliberately NOT self-service — see PlatformApiKeyAuthFilter. The operator
// team collects the client's nick/domain/admin credentials through whatever channel they use, and
// enters them here once; from that point on the client manages everything themselves through the
// normal admin panel (already fully built) under their own slug/domain.
//
// POS-6: the UvoPOS backend calls this too, when a company activates the e-commerce module, with the
// `pos` block — and then the same step creates the PosConnection with the secrets the POS generated.
// In both cases the owner is invited: nobody chooses someone else's password anymore.
@Service
public class StoreOnboardingServiceImpl implements StoreOnboardingService {

    /**
     * F08. El mismo nombre que usó {@code V15__seed_permissions.sql} para las tiendas que ya existían
     * entonces, para que una tienda nueva y una antigua se vean igual en la pantalla de Roles.
     */
    private static final String OWNER_ROLE_NAME = "Administrador";

    /** La clave de {@code settings} que lee y escribe {@code SettingsServiceImpl} para «Ajustes generales». */
    private static final String STORE_NAME_KEY = "store_name";

    /** La URL de la API del POS que muestra «Ajustes generales» ({@code SettingsServiceImpl}). */
    private static final String POS_API_URL_KEY = "pos_api_url";

    /**
     * POS-6. El primer entero del cerrojo de alta por empresa ({@code pg_advisory_xact_lock(int, int)}). La forma de
     * dos enteros es un espacio de claves aparte de la de un {@code bigint} que usan los jobs
     * ({@code AdvisoryLock}), así que no puede chocar con ellos.
     */
    static final int ONBOARDING_LOCK_CLASS = 7312;

    /** POS-6. El largo mínimo de la clave del token: lo genera una máquina, no lo teclea nadie. */
    static final int MIN_GENERATED_KEY_LENGTH = 32;

    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final StoreSettingsRepository storeSettingsRepository;
    private final SettingRepository settingRepository;
    private final PosConnectionRepository posConnectionRepository;
    private final PosIntegrationPolicy posPolicy;
    private final AdminInvitationMailer invitationMailer;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbcTemplate;
    private final StorePublicUrlResolver publicUrls;
    private final AuditTrail auditTrail;

    public StoreOnboardingServiceImpl(StoreRepository storeRepository, UserRepository userRepository,
                                      RoleRepository roleRepository, PermissionRepository permissionRepository,
                                      StoreSettingsRepository storeSettingsRepository,
                                      SettingRepository settingRepository,
                                      PosConnectionRepository posConnectionRepository,
                                      PosIntegrationPolicy posPolicy, AdminInvitationMailer invitationMailer,
                                      ApplicationEventPublisher events, JdbcTemplate jdbcTemplate,
                                      StorePublicUrlResolver publicUrls, AuditTrail auditTrail) {
        this.storeRepository = storeRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.storeSettingsRepository = storeSettingsRepository;
        this.settingRepository = settingRepository;
        this.posConnectionRepository = posConnectionRepository;
        this.posPolicy = posPolicy;
        this.invitationMailer = invitationMailer;
        this.events = events;
        this.jdbcTemplate = jdbcTemplate;
        this.publicUrls = publicUrls;
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional
    public StoreOnboardingResponse createStore(StoreOnboardingCommand command) {
        String slug = command.slug().toLowerCase().trim();
        StoreOnboardingCommand.PosLink pos = command.pos();
        if (pos != null) {
            requireUsablePosLink(pos);
            // POS-6. Idempotente por la empresa del POS, que es la clave natural: company_id es UNIQUE en
            // pos_connections. El cerrojo va antes de mirar, para que dos reintentos simultáneos no vean los dos
            // "no existe" y el segundo acabe en un 500 por la restricción única del nick.
            lockOnboardingOf(pos.companyId());
            Optional<PosConnection> existing = posConnectionRepository.findByCompanyId(pos.companyId());
            if (existing.isPresent()) {
                return replay(existing.get(), slug, command);
            }
        }

        if (storeRepository.existsBySlug(slug)) {
            throw new BusinessException("Ese nick ya está en uso");
        }
        String domain = normalizeDomain(command.domain());
        if (domain != null && storeRepository.existsByDomain(domain)) {
            throw new BusinessException("Ese dominio ya está en uso");
        }

        Store store = new Store();
        store.setName(command.storeName());
        store.setSlug(slug);
        store.setDomain(domain);
        store.setStatus(StoreStatus.ACTIVE);
        Store savedStore = storeRepository.save(store);

        User admin = new User();
        admin.setStore(savedStore);
        admin.setName(command.adminName());
        admin.setEmail(command.adminEmail());
        // POS-6. El dueño nace sin contraseña y con una invitación de un solo uso, como un administrador invitado
        // desde el panel (PROD-06): antes la elegía el operador, que la conocía para siempre. Mientras no la
        // acepte no puede entrar: adminLogin compara contra un hash nulo y el encoder responde que no.
        admin.setPassword(null);
        invitationMailer.issue(admin);
        admin.setActive(true);
        admin.setAdmin(true);
        admin.setRoles(new HashSet<>(Set.of(ownerRole(savedStore))));
        User savedAdmin = userRepository.save(admin);

        savedStore.setOwnerUserId(savedAdmin.getId());
        storeRepository.save(savedStore);

        seedStoreName(savedStore);
        if (pos != null) {
            connect(savedStore, pos);
        }

        // PROD-18. Con la tienda nueva dicha explícitamente: el Host de esta llamada puede ser el de otra tienda.
        // Del bloque pos solo la empresa: ni el token, ni el secreto, ni la URL.
        auditTrail.record(AuditEntry.of(AuditAction.STORE_CREATE, AuditTargetType.STORE, savedStore.getId())
                .forStore(savedStore.getId())
                .details(AuditDiff.create()
                        .with("slug", slug)
                        .with("domain", domain)
                        .with("name", command.storeName())
                        .with("ownerUserId", savedAdmin.getId())
                        .with("ownerEmail", savedAdmin.getEmail())
                        .with("origin", pos != null ? "POS" : "OPERATOR")
                        .with("posCompanyId", pos != null ? pos.companyId() : null)));

        // El correo sale después del commit (StoreOwnerInvitationListener): si el alta se deshiciera, el dueño
        // recibiría un enlace con un token que no existe.
        events.publishEvent(new StoreOwnerInvitedEvent(savedAdmin.getId()));
        return toResponse(savedStore, savedAdmin, true);
    }

    /**
     * POS-6. Lo que el bloque {@code pos} tiene que cumplir más allá de sus anotaciones, comprobado antes de escribir
     * nada.
     *
     * <p>La integración tiene que estar encendida en la plataforma: con {@code POS_ENABLED=false},
     * {@code /api/sync/**} responde 404, así que la tienda quedaría creada y el POS no podría cargarle el catálogo
     * — un alta a medias. Y el token tiene que ser de esa empresa, con una clave de máquina.
     *
     * <p>Ningún mensaje repite el token: vuelve en la respuesta.
     */
    private void requireUsablePosLink(StoreOnboardingCommand.PosLink pos) {
        if (!posPolicy.isEnabled()) {
            throw new BusinessException("La integración con UvoPOS está apagada en esta plataforma (POS_ENABLED): "
                    + "sin ella el POS no podría cargar el catálogo de la tienda.");
        }
        Long tokenCompany = PosApiKey.companyIdOf(pos.apiKey()).orElse(null);
        if (!pos.companyId().equals(tokenCompany)) {
            throw new BusinessException("El token de la API no tiene el formato uvp_<empresa>_<clave> de la empresa "
                    + pos.companyId() + ".");
        }
        if (PosApiKey.secretLength(pos.apiKey()) < MIN_GENERATED_KEY_LENGTH) {
            throw new BusinessException("La clave del token de la API debe tener al menos " + MIN_GENERATED_KEY_LENGTH
                    + " caracteres.");
        }
    }

    /**
     * POS-6. Un cerrojo por empresa que dura lo que la transacción. Va por {@code JdbcTemplate}, que usa la misma
     * conexión que la transacción de JPA. La clave es el hash de la empresa: dos empresas con el mismo hash solo
     * se esperarían una a la otra, nunca se mezclarían.
     */
    private void lockOnboardingOf(Long companyId) {
        jdbcTemplate.query("select pg_advisory_xact_lock(?, ?)", (ResultSetExtractor<Void>) rs -> null,
                ONBOARDING_LOCK_CLASS, Long.hashCode(companyId));
    }

    /**
     * POS-6. La empresa ya tiene tienda: es un reintento del POS, y <b>no escribe nada</b>.
     *
     * <p>Si el reintento es el mismo alta (mismo nick, mismos secretos, mismo dueño), devuelve la tienda que ya
     * existe. Si algo de eso cambió, se rechaza en vez de actualizar. Actualizar los secretos parecería más
     * cómodo, pero un reintento atrasado de una activación anterior volvería a poner unos secretos que el POS ya
     * no tiene, y desde ese momento ninguna llamada autenticaría. Y tampoco puede revivir ni cambiarle las
     * credenciales a una conexión desactivada o a una tienda suspendida, que es cosa de PROD-23.
     *
     * <p>Tampoco deja fila de auditoría (PROD-18): no cambia nada, y el alta original ya quedó registrada.
     */
    private StoreOnboardingResponse replay(PosConnection connection, String slug, StoreOnboardingCommand command) {
        StoreOnboardingCommand.PosLink pos = command.pos();
        Store store = connection.getStore();
        if (!store.getSlug().equals(slug)) {
            throw new BusinessException("La empresa " + pos.companyId() + " ya tiene la tienda " + store.getSlug() + ".");
        }
        User owner = store.getOwnerUserId() == null ? null : userRepository.findById(store.getOwnerUserId()).orElse(null);
        boolean sameAlta = owner != null
                && owner.getEmail().equalsIgnoreCase(command.adminEmail().trim())
                && constantTimeEquals(connection.getApiKey(), pos.apiKey())
                && constantTimeEquals(connection.getWebhookSecret(), pos.webhookSecret());
        if (!sameAlta) {
            throw new BusinessException("La empresa " + pos.companyId() + " ya está conectada con otras credenciales.");
        }
        return toResponse(store, owner, false);
    }

    /**
     * POS-6. La conexión con el POS, con los secretos que generó el POS (los cifra {@code EncryptedStringConverter}
     * al guardar), y los dos ajustes que la hacen operativa: {@code pos_sync_enabled}, sin el cual
     * {@code PosIntegrationPolicy} trata la tienda como apagada, y {@code pos_api_url}, que muestra el panel.
     */
    private void connect(Store store, StoreOnboardingCommand.PosLink pos) {
        PosConnection connection = new PosConnection();
        connection.setStore(store);
        connection.setCompanyId(pos.companyId());
        connection.setCompanyName(pos.companyName());
        connection.setApiUrl(pos.apiUrl());
        connection.setApiKey(pos.apiKey());
        connection.setWebhookSecret(pos.webhookSecret());
        connection.setActive(true);
        connection.setConnectedAt(Instant.now());
        posConnectionRepository.save(connection);

        seedSetting(store, PosIntegrationPolicy.STORE_SETTING_KEY, "true");
        seedSetting(store, POS_API_URL_KEY, pos.apiUrl());
    }

    private static boolean constantTimeEquals(String stored, String received) {
        if (stored == null || received == null) {
            return false;
        }
        return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    @Transactional
    public StoreOnboardingResponse updateDomain(Long storeId, String domain) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new NoSuchElementException("Store " + storeId + " not found"));

        String normalized = normalizeDomain(domain);
        if (normalized != null && !normalized.equals(store.getDomain()) && storeRepository.existsByDomain(normalized)) {
            throw new BusinessException("Ese dominio ya está en uso");
        }

        // PROD-04. Un dominio distinto es un dominio sin comprobar: si la verificación se heredara, el
        // primer correo saldría con un enlace a un dominio cuyo DNS puede no apuntar aquí todavía. Volver
        // al subdominio de plataforma mientras tanto es el estado seguro, y funciona siempre.
        String previous = store.getDomain();
        boolean wasVerified = store.getDomainVerifiedAt() != null;
        if (!java.util.Objects.equals(normalized, store.getDomain())) {
            store.setDomainVerifiedAt(null);
        }
        store.setDomain(normalized);
        Store saved = storeRepository.save(store);
        AuditDiff diff = AuditDiff.create().value("domain", previous, normalized);
        if (diff.hasChanges()) {
            auditTrail.record(AuditEntry.of(AuditAction.STORE_DOMAIN_UPDATE, AuditTargetType.STORE, saved.getId())
                    .forStore(saved.getId())
                    .details(diff.with("verificationCleared", wasVerified)));
        }
        User admin = saved.getOwnerUserId() == null ? null : userRepository.findById(saved.getOwnerUserId()).orElse(null);
        return toResponse(saved, admin, false);
    }

    /**
     * PROD-04. Deja constancia de que el dominio propio de la tienda ya apunta aquí, que es lo que habilita
     * su uso en los enlaces que salen (correos, retornos de pasarela, webhooks).
     *
     * <p><b>No sondea DNS ni pide el certificado</b>: registra la comprobación que hizo el operador. Hacerlo
     * desde aquí exigiría salir a la red desde la petición y aún así no diría gran cosa —la resolución
     * depende de desde dónde se pregunte, y el proxy inverso todavía no existe (PROD-03)—. Automatizarlo
     * cuando haya staging es una mejora, no un requisito: lo que hacía falta era que existiera el estado.
     *
     * @param verified false deshace la verificación, para cuando un dominio deja de funcionar y hay que
     *        devolver los enlaces al subdominio sin perder el dominio configurado.
     */
    @Override
    @Transactional
    public StoreOnboardingResponse setDomainVerified(Long storeId, boolean verified) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new NoSuchElementException("Store " + storeId + " not found"));
        if (verified && (store.getDomain() == null || store.getDomain().isBlank())) {
            throw new BusinessException("Esta tienda no tiene dominio propio que verificar");
        }
        boolean wasVerified = store.getDomainVerifiedAt() != null;
        store.setDomainVerifiedAt(verified ? Instant.now() : null);
        Store saved = storeRepository.save(store);
        AuditDiff diff = AuditDiff.create().value("verified", wasVerified, verified);
        if (diff.hasChanges()) {
            auditTrail.record(AuditEntry.of(AuditAction.STORE_DOMAIN_VERIFY, AuditTargetType.STORE, saved.getId())
                    .forStore(saved.getId())
                    .details(diff.with("domain", saved.getDomain())));
        }
        User admin = saved.getOwnerUserId() == null ? null : userRepository.findById(saved.getOwnerUserId()).orElse(null);
        return toResponse(saved, admin, false);
    }

    /**
     * F08. El rol de dueño de la tienda, con el catálogo de permisos entero.
     *
     * <p>Sin esto el alta entregaba un administrador <b>sin ningún rol</b>, y como
     * {@code AuthController.adminAuthorities} compone el token con {@code ROLE_ADMIN} más los permisos
     * de sus roles, el dueño entraba con una sola autoridad. El síntoma no era ni un 403 claro: el menú
     * del panel se dibuja a partir de esos permisos, así que el cliente veía un panel sin secciones
     * —incluida la de Roles, la única que habría servido para arreglarlo— y la salida era SQL a mano.
     * Funcionaba solo para las tiendas anteriores a {@code V15__seed_permissions.sql}, que les creó
     * este mismo rol por migración.
     *
     * <p><b>Esto es una foto del catálogo en el momento del alta.</b> Un permiso nuevo añadido por una
     * migración futura no aparece aquí por sí solo: hay que concederlo también a los roles que ya
     * existen, como hace {@code V19__order_refunds.sql} con {@code orders.refund} (y lo hace de forma
     * genérica, sobre cualquier rol que tenga {@code orders.manage}, así que cubre también los roles
     * que crea este método). Mantener ese patrón.
     */
    private Role ownerRole(Store store) {
        Role role = new Role();
        role.setStore(store);
        role.setName(OWNER_ROLE_NAME);
        // guardName se queda en su default "web", que es lo que espera UNIQUE(store_id, name, guard_name).
        role.setPermissions(new HashSet<>(permissionRepository.findAll()));
        return roleRepository.save(role);
    }

    /**
     * PROD-24a. El nombre que el operador escribió en el alta, en los dos sitios donde la tienda se presenta.
     *
     * <p>Hay dos, y los dos arrancaban en «UvoStore»: {@code store_settings.store_name} (la identidad que
     * dibuja el storefront en el encabezado, el pie y las páginas legales, y que edita «Apariencia») y la
     * clave {@code store_name} de {@code settings} (la que muestra «Ajustes generales»). Una tienda recién
     * creada se presentaba con el nombre de la plataforma hasta que su dueño se fijaba en cambiarlo, y la
     * lista de tiendas y los correos ({@code Store.name}) decían otra cosa: el nombre del alta.
     *
     * <p><b>No pasa por {@code StoreSettingsService} ni {@code SettingsService}</b>: los dos leen la tienda
     * de {@code TenantContext}, que aquí no existe todavía —la ruta de plataforma es justo la que crea la
     * tienda—. Por eso se escriben las filas directamente con las tablas de cada uno. La fila de
     * {@code store_settings} se crea con el resto de sus valores por defecto, los mismos que le daría
     * {@code StoreSettingsServiceImpl.currentOrCreate()} la primera vez que alguien la pidiera.
     *
     * <p>Solo es el valor <b>inicial</b>: el dueño lo cambia después desde el panel y ese cambio manda.
     * Tampoco corrige las tiendas que ya existían con «UvoStore».
     */
    private void seedStoreName(Store store) {
        StoreSettings branding = new StoreSettings();
        branding.setStore(store);
        branding.setStoreName(store.getName());
        storeSettingsRepository.save(branding);

        seedSetting(store, STORE_NAME_KEY, store.getName());
    }

    /** Una fila de {@code settings} de una tienda recién creada, sin pasar por {@code TenantContext}. */
    private void seedSetting(Store store, String key, String value) {
        Setting setting = new Setting();
        setting.setStore(store);
        setting.setSettingKey(key);
        setting.setValue(value);
        settingRepository.save(setting);
    }

    private String normalizeDomain(String domain) {
        return domain == null || domain.isBlank() ? null : domain.toLowerCase().trim();
    }

    private StoreOnboardingResponse toResponse(Store store, User admin, boolean created) {
        return new StoreOnboardingResponse(
                store.getId(), store.getName(), store.getSlug(), store.getDomain(),
                admin == null ? null : admin.getId(), admin == null ? null : admin.getEmail(),
                store.getDomainVerifiedAt(),
                publicUrls.storefrontOrigin(store),
                publicUrls.storefrontUrl(store, "/admin/login"),
                created
        );
    }
}
