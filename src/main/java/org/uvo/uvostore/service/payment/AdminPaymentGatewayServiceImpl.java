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
        // M3: MercadoPago's webhook signature can only be verified with a per-merchant secret, so
        // enabling the gateway without one would mean either an unverifiable webhook or a silent
        // failure in production. Refused here, at the point of configuration, where the person can
        // actually do something about it.
        if (gateway == PaymentGatewayType.MERCADOPAGO && config.isEnabled()) {
            String webhookSecret = config.getCredentials().get("webhookSecret");
            if (webhookSecret == null || webhookSecret.isBlank()) {
                throw new IllegalArgumentException(
                        "Para activar MercadoPago debes configurar el secreto de webhook "
                                + "(Tus integraciones > Webhooks en el panel de MercadoPago).");
            }
        }

        return toDto(repository.save(config));
    }

    private PaymentGatewayConfigDto toDto(PaymentGatewayConfig config) {
        Map<String, Boolean> credentialsSet = new HashMap<>();
        config.getCredentials().forEach((key, value) -> credentialsSet.put(key, value != null && !value.isBlank()));
        return new PaymentGatewayConfigDto(config.getId(), config.getGateway().name(), config.isEnabled(), credentialsSet);
    }
}
