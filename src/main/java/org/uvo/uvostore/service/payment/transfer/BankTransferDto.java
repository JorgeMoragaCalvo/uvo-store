package org.uvo.uvostore.service.payment.transfer;

import org.uvo.uvostore.entity.payment.BankTransferSettings;
import org.uvo.uvostore.entity.payment.enums.BankAccountType;

/**
 * IMP-07. Los datos de la transferencia de una tienda.
 *
 * <p>El mismo registro sirve al panel y al comprador: no hay nada privado en él, son los datos que la tienda publica
 * para que le paguen. {@code accountTypeLabel} es el nombre que se muestra («Cuenta corriente»).
 */
public record BankTransferDto(
        boolean enabled,
        String holderName,
        String holderRut,
        String bankName,
        BankAccountType accountType,
        String accountTypeLabel,
        String accountNumber,
        String noticeEmail,
        String instructions,
        int paymentWindowHours) {

    /** Lo que ve una tienda que nunca la configuró. */
    public static BankTransferDto empty() {
        return new BankTransferDto(false, null, null, null, null, null, null, null, null,
                BankTransferSettings.DEFAULT_PAYMENT_WINDOW_HOURS);
    }

    public static BankTransferDto of(BankTransferSettings settings) {
        BankAccountType type = settings.getAccountType();
        return new BankTransferDto(settings.isEnabled(), settings.getHolderName(), settings.getHolderRut(),
                settings.getBankName(), type, type == null ? null : type.label(), settings.getAccountNumber(),
                settings.getNoticeEmail(), settings.getInstructions(), settings.getPaymentWindowHours());
    }
}
