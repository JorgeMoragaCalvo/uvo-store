package org.uvo.uvostore.service.payment;

/**
 * IMP-02. Si la plataforma deja activar una pasarela, para que el panel no ofrezca un interruptor que el backend
 * rechazará. {@code reason} es null cuando está disponible.
 */
public record PaymentGatewayAvailabilityDto(String gateway, boolean available, String reason) {
}
