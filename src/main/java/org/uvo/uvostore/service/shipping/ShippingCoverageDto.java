package org.uvo.uvostore.service.shipping;

import java.util.List;

/**
 * One region a store delivers to, plus the communes it discriminates by inside it.
 *
 * @param communes empty means the zone covers the whole region — exactly the semantics of
 *        {@code ShippingRateServiceImpl.findZone}, which matches on region alone when a zone has no
 *        commune list. The storefront uses it the same way: only offer a commune picker when there
 *        is something to pick.
 */
public record ShippingCoverageDto(String region, List<String> communes) {
}
