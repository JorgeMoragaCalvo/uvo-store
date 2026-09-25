package org.uvo.uvostore.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * F06. El único sitio que decide cuántos decimales tiene el dinero.
 *
 * <p><b>El peso chileno no tiene centavos</b>, pero el cálculo los fabricaba: el IVA salía con
 * {@code setScale(2)}, el descuento porcentual también, y el total se componía sin redondear. Las
 * pasarelas, en cambio, cobran entero. Un producto de $9.990 con 19 % daba un total de $11.888,10, se
 * cobraban $11.888, y {@code OrderStatusServiceImpl.markPaid} —que compara exacto, y hace bien—
 * rechazaba el importe: la orden se quedaba PENDING, sin stock descontado, con alerta a Sentry y
 * excluida de la conciliación para siempre. El cliente pagaba y su pedido no avanzaba.
 *
 * <p>Y no era un caso raro: {@code subtotal × 0,19} solo da entero si el subtotal es múltiplo de 100,
 * y los precios chilenos acaban en 90 o 990. Como el alta de una tienda no crea ningún ajuste, el
 * camino por defecto es precios sin IVA incluido — es decir, casi ninguna orden de una tienda nueva se
 * podía marcar como pagada.
 *
 * <p>Por eso se redondea <b>en el origen</b>, antes de persistir la orden, y no en la comparación:
 * arreglar solo la comparación dejaría la base guardando importes que nadie puede cobrar, y el
 * documento tributario que se manda al POS ({@code PosOrderPayloadMapper}) seguiría llevando decimales.
 */
public final class Money {

    /**
     * Escala de la unidad mínima de la moneda. Cero porque el CLP no tiene fracción, y hoy CLP es lo
     * único que el sistema puede cobrar de verdad: Webpay y MercadoPago llevan {@code "CLP"} fijo en
     * el código. El día que se admita otra divisa esto deja de ser una constante y pasa a depender de
     * la moneda de la tienda — pero eso exige antes validar el ajuste {@code currency} contra un
     * catálogo, que es F17. Una tabla de escalas hoy sería adivinar.
     */
    private static final int CLP_SCALE = 0;

    private Money() {
    }

    /** El importe a la unidad mínima de la moneda. Null entra y null sale, para no tapar un ausente. */
    public static BigDecimal round(BigDecimal amount) {
        return amount == null ? null : amount.setScale(CLP_SCALE, RoundingMode.HALF_UP);
    }
}
