package org.uvo.uvostore.service.pos;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.SettingRepository;

/**
 * PROD-12. El único sitio que decide si la integración con UvoPOS está operativa.
 *
 * <p>Hacen falta dos síes, y son de naturaleza distinta:
 *
 * <ol>
 *   <li><b>{@code app.pos.enabled}, de plataforma, apagado por defecto.</b> Notificar una orden al POS
 *       <b>emite un documento tributario</b>, y el contrato de idempotencia del receptor sigue sin
 *       confirmar — es exactamente la razón por la que {@code app.pos.retry.enabled} ya estaba apagado.
 *       Tener el reintento contenido y la emisión encendida era incoherente: el riesgo que justifica lo
 *       primero es el mismo que corre lo segundo.</li>
 *   <li><b>{@code pos_sync_enabled}, por tienda.</b> Ya existía como ajuste, con su interruptor en el
 *       panel… y <b>ningún código lo leía</b>: apagarlo no apagaba nada. Es el mismo patrón del
 *       {@code isOnSale='false'} de F11 y del {@code sendInvitation='false'} de PROD-06 — un control que
 *       miente. Aquí pasa a decidir de verdad.</li>
 * </ol>
 *
 * <p>Se lee con {@code SettingRepository} y no por un servicio de ajustes para no arrastrar el resto del
 * formulario, igual que hace {@code ShippingRateServiceImpl} con el origen de despacho.
 */
@Component
public class PosIntegrationPolicy {

    // Pública por POS-6: el alta desde el POS la escribe al crear la tienda.
    public static final String STORE_SETTING_KEY = "pos_sync_enabled";

    private final boolean enabled;
    private final SettingRepository settingRepository;

    public PosIntegrationPolicy(@Value("${app.pos.enabled:false}") boolean enabled,
                                SettingRepository settingRepository) {
        this.enabled = enabled;
        this.settingRepository = settingRepository;
    }

    /**
     * El sí de la plataforma. Es lo que mira la puerta de la superficie de entrada
     * ({@code /api/sync/**}, {@code /api/webhooks/pos/**}), que llega antes de resolver la tienda.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Los dos síes. Es lo que mira todo lo que ya sabe de qué tienda habla.
     *
     * <p>Una tienda sin el ajuste escrito cuenta como <b>no</b>: el alta no crea ningún {@code Setting}
     * (lo anota F06), así que lo contrario encendería el POS en toda tienda nueva por omisión, que es la
     * clase de valor por defecto que PROD-12 cierra.
     */
    public boolean isEnabledFor(Store store) {
        if (!enabled || store == null) {
            return false;
        }
        return settingRepository.findByStoreIdAndSettingKey(store.getId(), STORE_SETTING_KEY)
                .map(setting -> Boolean.parseBoolean(setting.getValue()))
                .orElse(false);
    }
}
