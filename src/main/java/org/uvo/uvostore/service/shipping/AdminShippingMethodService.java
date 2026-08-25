package org.uvo.uvostore.service.shipping;

import java.util.List;
import java.util.Map;

// Ports Admin\Shipping\Methods\{Index,Create,Edit}.
public interface AdminShippingMethodService {
    List<ShippingMethodDto> list();
    ShippingMethodDto getById(Long id);
    ShippingMethodDto create(ShippingMethodCommand command);
    ShippingMethodDto update(Long id, ShippingMethodCommand command);
    void delete(Long id);
    ShippingMethodDto toggleStatus(Long id);
    ShippingMethodDto updateCredentials(Long id, Map<String, String> credentials);
}
