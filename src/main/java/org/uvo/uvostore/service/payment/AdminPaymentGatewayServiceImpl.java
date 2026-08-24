package org.uvo.uvostore.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.payment.PaymentGatewayConfig;
import org.uvo.uvostore.entity.payment.enums.PaymentGatewayType;
import org.uvo.uvostore.repository.PaymentGatewayConfigRepository;
import org.uvo.uvostore.security.TenantContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminPaymentGatewayServiceImpl implements AdminPaymentGatewayService {

    private final PaymentGatewayConfigRepository repository;

    public AdminPaymentGatewayServiceImpl(PaymentGatewayConfigRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentGatewayConfigDto> list() {
        return repository.findByStoreId(TenantContext.requireStoreId()).stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    public PaymentGatewayConfigDto upsert(PaymentGatewayType gateway, PaymentGatewayConfigCommand command) {
        Long storeId = TenantContext.requireStoreId();
        PaymentGatewayConfig config = repository.findByStoreIdAndGateway(storeId, gateway).orElseGet(() -> {
            PaymentGatewayConfig created = new PaymentGatewayConfig();
            created.setStore(TenantContext.requireCurrent());
            created.setGateway(gateway);
            return created;
        });
        config.setEnabled(command.enabled());
        if (command.credentials() != null) {
            Map<String, String> merged = new HashMap<>(config.getCredentials());
            merged.putAll(command.credentials());
            config.setCredentials(merged);
        }
        return toDto(repository.save(config));
    }

    private PaymentGatewayConfigDto toDto(PaymentGatewayConfig config) {
        Map<String, Boolean> credentialsSet = new HashMap<>();
        config.getCredentials().forEach((key, value) -> credentialsSet.put(key, value != null && !value.isBlank()));
        return new PaymentGatewayConfigDto(config.getId(), config.getGateway().name(), config.isEnabled(), credentialsSet);
    }
}
