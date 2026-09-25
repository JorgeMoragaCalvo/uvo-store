package org.uvo.uvostore.service.order;

import java.util.List;

public interface CartPricingService {
    /**
     * @param customerId F05: quién compra, cuando se sabe. El checkout lo pasa siempre —identifica al
     *        cliente por email antes de cotizar— y la cotización pública del carrito pasa null, porque
     *        ahí todavía no hay email y lo correcto es mostrar el mejor precio posible. Es lo que
     *        permite que el límite de usos por cliente se evalúe dentro del mismo cálculo que fija el
     *        total, en vez de en una segunda validación cuyo desacuerdo se ignoraba.
     */
    CartTotals price(List<CartLineCommand> lines, String couponCode, String region, String commune, Long customerId);
}
