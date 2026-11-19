package org.uvo.uvostore.service.payment.transfer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.uvo.uvostore.entity.payment.enums.BankAccountType;

/**
 * IMP-07. Lo que guarda el panel. Los campos pueden ir vacíos mientras la transferencia está apagada; al activarla,
 * {@link BankTransferService} exige todos salvo {@code instructions}. {@code paymentWindowHours} nulo deja el valor
 * que había (48 h en una tienda nueva).
 */
public record BankTransferCommand(
        boolean enabled,
        @Size(max = 120) String holderName,
        @Size(max = 20) String holderRut,
        @Size(max = 80) String bankName,
        BankAccountType accountType,
        @Size(max = 30) String accountNumber,
        @Email @Size(max = 255) String noticeEmail,
        @Size(max = 1000) String instructions,
        @Min(1) @Max(168) Integer paymentWindowHours) {
}
