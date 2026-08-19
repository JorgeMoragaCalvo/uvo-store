package org.uvo.uvostore.service.shipping;

import java.util.List;

// Ports Admin\Shipping\Rates\{Index,Create,Edit}.
public interface AdminShippingRateService {
    List<ShippingRateDto> list(Long methodId, Long zoneId);
    ShippingRateDto getById(Long id);
    ShippingRateDto create(ShippingRateCommand command);
    ShippingRateDto update(Long id, ShippingRateCommand command);
    void delete(Long id);
    ShippingRateDto toggleStatus(Long id);
}
