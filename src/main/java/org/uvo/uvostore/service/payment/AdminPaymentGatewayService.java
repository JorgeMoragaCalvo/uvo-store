package org.uvo.uvostore.service.payment;

import org.uvo.uvostore.entity.payment.enums.PaymentGatewayType;

import java.util.List;

public interface AdminPaymentGatewayService {
    List<PaymentGatewayConfigDto> list();
    PaymentGatewayConfigDto upsert(PaymentGatewayType gateway, PaymentGatewayConfigCommand command);
}
