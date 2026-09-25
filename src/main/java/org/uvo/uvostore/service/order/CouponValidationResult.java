package org.uvo.uvostore.service.order;

import org.uvo.uvostore.entity.order.Coupon;

/**
 * @param customerSpecific F05. El rechazo depende de <b>quién</b> compra, no del cupón: hoy solo el
 *        límite de usos por cliente. Importa porque la cotización pública del carrito no sabe quién
 *        es el cliente —el checkout lo identifica por email, y ese dato no existe en
 *        {@code /cart/calculate}—, así que este es el único motivo por el que el carrito pudo mostrar
 *        un descuento que el checkout no puede honrar. Los demás (código inexistente, caducado,
 *        agotado, mínimo no alcanzado) ya salían igual en la cotización.
 *        <p>Va marcado explícitamente y no deducido comparando dos validaciones: si mañana se añade
 *        otra comprobación que dependa del cliente, tiene que marcarse aquí — y hay un test que lo
 *        recuerda.
 */
public record CouponValidationResult(boolean valid, String reason, Coupon coupon, boolean customerSpecific) {

    public CouponValidationResult(boolean valid, String reason, Coupon coupon) {
        this(valid, reason, coupon, false);
    }
}
