package org.uvo.uvostore.service.payment.transfer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.audit.enums.AuditAction;
import org.uvo.uvostore.entity.audit.enums.AuditTargetType;
import org.uvo.uvostore.entity.payment.BankTransferSettings;
import org.uvo.uvostore.entity.payment.enums.BankAccountType;
import org.uvo.uvostore.repository.BankTransferSettingsRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.audit.AuditDiff;
import org.uvo.uvostore.service.audit.AuditEntry;
import org.uvo.uvostore.service.audit.AuditTrail;
import org.uvo.uvostore.service.settings.ChileanRut;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * IMP-07. El pago manual de la tienda: una transferencia bancaria que la tienda activa con sus datos.
 *
 * <p>Antes el pago manual era «contra entrega»: se ofrecía siempre, sin decirle al comprador cómo pagar, y la orden
 * se cancelaba a las 24 horas si nadie la tocaba. La matriz de homologación (GO_LIVE_CHECKLIST §10) pide instrucciones
 * visibles y un vencimiento conocido. Ahora:
 * <ul>
 *   <li>sin datos completos no se puede activar (y el CHECK de V37 lo repite);</li>
 *   <li>apagada, el checkout no la ofrece y una orden {@code manual} se rechaza ({@link #requireEnabled});</li>
 *   <li>el comprador ve los datos y la fecha límite en el checkout, en la confirmación y en el correo;</li>
 *   <li>{@code AbandonedOrderJob} cancela la orden al vencer ese mismo plazo.</li>
 * </ul>
 * La confirmación del pago la hace una persona con {@code orders.manage} («Marcar como pagado»), después de verlo en
 * el banco; el procedimiento está en {@code docs/guia-de-uso.md}.
 */
@Service
public class BankTransferService {

    /** Para el 400 del checkout: lo que lee el comprador. */
    public static final String UNAVAILABLE_REASON =
            "Esta tienda no recibe pagos por transferencia. Elige otro medio de pago.";

    private final BankTransferSettingsRepository repository;
    private final AuditTrail auditTrail;

    public BankTransferService(BankTransferSettingsRepository repository, AuditTrail auditTrail) {
        this.repository = repository;
        this.auditTrail = auditTrail;
    }

    /** Lo que muestra el panel; vacío y apagado si la tienda nunca la configuró. */
    @Transactional(readOnly = true)
    public BankTransferDto get() {
        return repository.findByStoreId(TenantContext.requireStoreId())
                .map(BankTransferDto::of)
                .orElseGet(BankTransferDto::empty);
    }

    /** Los datos para el comprador, solo si la tienda la ofrece. */
    @Transactional(readOnly = true)
    public Optional<BankTransferDto> offered(Long storeId) {
        return repository.findByStoreId(storeId)
                .filter(BankTransferSettings::isEnabled)
                .map(BankTransferDto::of);
    }

    /** Para el checkout: una orden por transferencia solo si la tienda la ofrece. */
    @Transactional(readOnly = true)
    public void requireEnabled(Long storeId) {
        if (offered(storeId).isEmpty()) {
            throw new BusinessException(UNAVAILABLE_REASON);
        }
    }

    /** Hasta cuándo puede pagar una orden creada en {@code createdAt}, con el plazo actual de la tienda. */
    public static Instant deadline(Instant createdAt, int paymentWindowHours) {
        return createdAt.plus(Duration.ofHours(paymentWindowHours));
    }

    @Transactional
    public BankTransferDto update(BankTransferCommand command) {
        Long storeId = TenantContext.requireStoreId();
        BankTransferSettings settings = repository.findByStoreId(storeId).orElseGet(() -> {
            BankTransferSettings created = new BankTransferSettings();
            created.setStore(TenantContext.requireCurrent());
            return created;
        });
        BankTransferDto before = settings.getId() == null ? null : BankTransferDto.of(settings);

        // Se valida antes de tocar la entidad: una gestionada a medio cambiar se escribiría con el siguiente flush.
        BankTransferCommand clean = new BankTransferCommand(command.enabled(), trimmed(command.holderName()),
                ChileanRut.normalize(command.holderRut()), trimmed(command.bankName()), command.accountType(),
                trimmed(command.accountNumber()), trimmed(command.noticeEmail()), trimmed(command.instructions()),
                command.paymentWindowHours());
        if (clean.enabled()) {
            requireComplete(clean);
        }
        settings.setHolderName(clean.holderName());
        settings.setHolderRut(clean.holderRut());
        settings.setBankName(clean.bankName());
        settings.setAccountType(clean.accountType());
        settings.setAccountNumber(clean.accountNumber());
        settings.setNoticeEmail(clean.noticeEmail());
        settings.setInstructions(clean.instructions());
        if (clean.paymentWindowHours() != null) {
            settings.setPaymentWindowHours(clean.paymentWindowHours());
        }
        settings.setEnabled(clean.enabled());

        BankTransferSettings saved = repository.save(settings);
        BankTransferDto after = BankTransferDto.of(saved);
        audit(storeId, before, after);
        return after;
    }

    private static void requireComplete(BankTransferCommand command) {
        List<String> missing = new ArrayList<>();
        if (command.holderName() == null) missing.add("titular");
        if (command.holderRut() == null) missing.add("RUT del titular");
        if (command.bankName() == null) missing.add("banco");
        if (command.accountType() == null) missing.add("tipo de cuenta");
        if (command.accountNumber() == null) missing.add("número de cuenta");
        if (command.noticeEmail() == null) missing.add("correo para el comprobante");
        if (!missing.isEmpty()) {
            throw new BusinessException("Para ofrecer transferencia bancaria completa: " + String.join(", ", missing)
                    + ". Es lo que el comprador necesita para pagarte.");
        }
    }

    // Una cuenta de destino que cambia es lo primero que se mira ante un fraude, así que van todos los valores, que
    // de todos modos se publican a cada comprador. Las instrucciones son texto libre: solo que cambiaron.
    private void audit(Long storeId, BankTransferDto before, BankTransferDto after) {
        BankTransferDto from = before == null ? BankTransferDto.empty() : before;
        AuditDiff diff = AuditDiff.create()
                .value("enabled", from.enabled(), after.enabled())
                .value("holderName", from.holderName(), after.holderName())
                .value("holderRut", from.holderRut(), after.holderRut())
                .value("bankName", from.bankName(), after.bankName())
                .value("accountType", typeName(from.accountType()), typeName(after.accountType()))
                .value("accountNumber", from.accountNumber(), after.accountNumber())
                .value("noticeEmail", from.noticeEmail(), after.noticeEmail())
                .value("paymentWindowHours", from.paymentWindowHours(), after.paymentWindowHours())
                .opaque("instructions", from.instructions(), after.instructions());
        if (before == null || diff.hasChanges()) {
            auditTrail.record(AuditEntry.of(AuditAction.BANK_TRANSFER_UPDATE, AuditTargetType.STORE, storeId)
                    .details(diff.with("created", before == null)));
        }
    }

    private static String typeName(BankAccountType type) {
        return type == null ? null : type.name();
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
