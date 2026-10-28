package org.uvo.uvostore.service.shipping;

import java.util.List;

/**
 * Una región a la que la tienda despacha, con las comunas que el comprador puede elegir en ella.
 *
 * @param region   el nombre canónico del catálogo ({@code GeoCatalog}).
 * @param communes nunca vacía: si alguna zona cubre la región entera, son todas sus comunas del catálogo;
 *                 si no, las que nombran las zonas. En orden alfabético. El checkout siempre pide una.
 */
public record ShippingCoverageDto(String region, List<String> communes) {
}
