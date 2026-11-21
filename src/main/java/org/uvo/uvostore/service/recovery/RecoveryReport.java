package org.uvo.uvostore.service.recovery;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * IMP-09. El informe de recuperación: lo que la base restaurada dice, al lado de lo que dicen las pasarelas, para
 * decidir qué hay que corregir a mano antes de volver a admitir tráfico. Va por tienda.
 *
 * <p>No trae datos personales del comprador (ni correo, ni nombre, ni dirección): número de orden, importes y
 * referencias de la pasarela bastan para buscar la operación en el portal del proveedor.
 *
 * @param from         desde cuándo se compararon las órdenes y reembolsos movidos (además de los pendientes de
 *                     cualquier fecha)
 * @param truncated    si alguna lista llegó a su tope: el informe está incompleto y hay que acotar {@code from}
 * @param attention    cuántos hallazgos piden una decisión, por clasificación
 * @param credentials  la sonda de descifrado: con fallas, nada de lo que use credenciales es confiable
 */
public record RecoveryReport(Instant generatedAt, Instant from, boolean recoveryMode, boolean truncated,
                             CredentialDecryptProbe.Result credentials, Map<String, Long> attention,
                             List<StoreSection> stores) {

    public record StoreSection(Long storeId, String storeSlug, List<PaymentFinding> payments,
                               List<RefundFinding> refunds, List<OutboxFinding> outbox) {
    }

    /**
     * Una orden cobrada por pasarela.
     *
     * @param localRefunded lo que la base da por devuelto (reembolsos completados)
     * @param classification ver {@link PaymentCheck}
     */
    public record PaymentFinding(Long orderId, String orderNumber, String method, String localPaymentStatus,
                                 String localOrderStatus, BigDecimal localTotal, BigDecimal localRefunded,
                                 String providerStatus, BigDecimal providerAmount, BigDecimal providerRefunded,
                                 String providerReference, PaymentCheck classification, String detail) {
    }

    public record RefundFinding(Long refundId, Long orderId, String orderNumber, String method, BigDecimal amount,
                                String status, Instant createdAt, RefundCheck classification, String detail) {
    }

    /**
     * Una fila del outbox sin terminar. Con el informe en la mano, cada una se reintenta o se cierra desde «Envíos
     * fallidos» después de retenerla ({@code POST /api/platform/recovery/outbox/hold}).
     */
    public record OutboxFinding(Long outboxId, String type, String status, String orderNumber, Long companyId,
                                int attempts, Instant createdAt, OutboxCheck classification) {
    }

    public enum PaymentCheck {
        /** La base y la pasarela dicen lo mismo. */
        COINCIDE,
        /** La pasarela cobró y la base no lo sabe: confirmarlo a mano (o dejar que la conciliación lo haga al salir). */
        PROVEEDOR_PAGADO_LOCAL_NO,
        /** La base dice pagada y la pasarela no tiene el cobro. */
        LOCAL_PAGADO_PROVEEDOR_NO,
        /** Los dos dicen pagada, por montos distintos. */
        MONTO_DISTINTO,
        /** La pasarela devolvió un monto distinto del que la base tiene registrado. */
        REEMBOLSO_DISTINTO,
        /** No se pudo preguntar (sin credenciales, pasarela caída, error). Revisar en el portal del proveedor. */
        NO_CONSULTABLE
    }

    public enum RefundCheck {
        /** Completado o rechazado en la base; su efecto en el saldo se ve en el hallazgo de pago de la orden. */
        REGISTRADO,
        /** Sin resolver, y la pasarela ya devolvió ese dinero: resolverlo como completado. */
        PROVEEDOR_DEVOLVIO,
        /** Sin resolver, y la pasarela no devolvió nada más de lo registrado: resolverlo como fallido. */
        PROVEEDOR_NO_DEVOLVIO,
        /** Sin resolver, y lo devuelto no calza con ninguna de las dos lecturas. */
        NO_CONCLUYENTE,
        /** Sin resolver, y la pasarela no informa lo devuelto (o no se pudo preguntar). */
        NO_CONSULTABLE
    }

    public enum OutboxCheck {
        /** Una venta al POS que pudo haber llegado. Hasta que el POS tenga la consulta por clave (IMP-08), se revisa a mano. */
        INCIERTO_POS,
        /** Un correo que pudo haber salido. */
        INCIERTO_CORREO
    }
}
