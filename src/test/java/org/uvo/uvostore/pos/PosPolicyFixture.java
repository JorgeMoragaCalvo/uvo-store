package org.uvo.uvostore.pos;

import org.uvo.uvostore.entity.settings.Setting;
import org.uvo.uvostore.repository.SettingRepository;
import org.uvo.uvostore.service.pos.PosIntegrationPolicy;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PROD-12. Una {@link PosIntegrationPolicy} armada a mano para los tests unitarios de esta familia.
 *
 * <p>La política necesita dos síes —la propiedad de plataforma y el ajuste {@code pos_sync_enabled} de
 * la tienda—, así que construirla exige simular el repositorio de ajustes. Vive aquí y no repetido en
 * cada test porque lo usan {@code PosOrderNotifierTest} y {@code PosNotifierDisabledTest}, y porque las
 * tres combinaciones que importan conviene tenerlas con nombre.
 */
final class PosPolicyFixture {

    private PosPolicyFixture() {
    }

    /** Los dos síes: plataforma habilitada y la tienda con la sincronización encendida. */
    static PosIntegrationPolicy enabled() {
        return new PosIntegrationPolicy(true, settingsSaying("true"));
    }

    /** El interruptor de plataforma apagado, que es el valor que se despacha. */
    static PosIntegrationPolicy platformDisabled() {
        return new PosIntegrationPolicy(false, settingsSaying("true"));
    }

    /** La plataforma lo permite y la tienda no lo quiere: la mitad que hasta PROD-12 no decidía nada. */
    static PosIntegrationPolicy storeDisabled() {
        return new PosIntegrationPolicy(true, settingsSaying("false"));
    }

    private static SettingRepository settingsSaying(String value) {
        SettingRepository repository = mock(SettingRepository.class);
        Setting setting = new Setting();
        setting.setSettingKey("pos_sync_enabled");
        setting.setValue(value);
        when(repository.findByStoreIdAndSettingKey(anyLong(), eq("pos_sync_enabled")))
                .thenReturn(Optional.of(setting));
        return repository;
    }
}
