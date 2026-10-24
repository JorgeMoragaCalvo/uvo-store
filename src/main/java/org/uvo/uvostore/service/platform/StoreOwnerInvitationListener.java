package org.uvo.uvostore.service.platform;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.service.security.AdminInvitationMailer;

/**
 * POS-6. Manda la invitación al dueño de una tienda recién creada, <b>después del commit</b> del alta.
 *
 * <p>Antes no: si el alta se deshiciera después de enviar, el dueño recibiría un enlace con un token que no
 * existe. La invitación de {@code UserServiceImpl} sí se envía dentro de la transacción, pero allí nada se
 * deshace después del envío; aquí el alta tiene más pasos.
 *
 * <p><b>No es durable.</b> Si el proceso cae entre el commit y el envío, o el SMTP falla, el correo no sale y queda
 * en el log y en Sentry. El dueño no queda sin salida: «¿Olvidaste tu contraseña?» funciona con una cuenta sin
 * contraseña y cierra la invitación pendiente ({@code AuthController.adminResetPassword}). El reenvío desde la
 * plataforma es PROD-23. El outbox no sirve para esto tal como está: sus filas cuelgan de una orden.
 *
 * <p><b>Un fallo aquí no cambia la respuesta del alta</b>, que ya está confirmada: Spring corre los oyentes
 * {@code AFTER_COMMIT} dentro de {@code afterCompletion} y {@code TransactionSynchronizationUtils} captura y registra
 * lo que lancen (comprobado con una mordida: sin el {@code try}, el alta seguía respondiendo 200). El {@code try} está
 * para que el fallo quede atribuido: una línea con el usuario y un evento en Sentry, en vez del registro genérico de
 * Spring, que no avisa a nadie. Por eso la carga también va dentro, con su propia transacción de solo lectura.
 */
@Component
public class StoreOwnerInvitationListener {

    private static final Logger log = LoggerFactory.getLogger(StoreOwnerInvitationListener.class);

    private final UserRepository userRepository;
    private final AdminInvitationMailer invitationMailer;
    private final TransactionTemplate readOnly;

    public StoreOwnerInvitationListener(UserRepository userRepository, AdminInvitationMailer invitationMailer,
                                        PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.invitationMailer = invitationMailer;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        // REQUIRES_NEW, no la propagación por defecto: en AFTER_COMMIT los recursos de la transacción del alta
        // siguen ligados al hilo, y con REQUIRED esto se uniría a una transacción ya confirmada. Es la misma razón
        // por la que Spring exige REQUIRES_NEW a un @Transactional sobre un oyente transaccional.
        this.readOnly.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOwnerInvited(StoreOwnerInvitedEvent event) {
        try {
            readOnly.executeWithoutResult(status -> {
                User owner = userRepository.findById(event.userId()).orElse(null);
                if (owner == null || owner.getInvitationToken() == null) {
                    // Ya no hay a quién invitar: se borró, o ya entró. No es un error de esta tienda.
                    log.warn("Invitación del dueño sin enviar: el usuario {} ya no tiene una invitación pendiente",
                            event.userId());
                    return;
                }
                invitationMailer.send(owner, owner.getStore());
            });
        } catch (RuntimeException e) {
            log.error("Invitación del dueño sin enviar user_id={} error={}", event.userId(), e.getMessage(), e);
            Sentry.captureException(e);
        }
    }
}
