package org.uvo.uvostore.service.shipping;

import java.util.List;

// Ports Admin\Shipping\Zones\{Index,Create,Edit}.
public interface AdminShippingZoneService {
    List<ShippingZoneDto> list(String search);
    ShippingZoneDto getById(Long id);
    ShippingZoneDto create(ShippingZoneCommand command);
    ShippingZoneDto update(Long id, ShippingZoneCommand command);
    void delete(Long id);
    ShippingZoneDto toggleStatus(Long id);
}
