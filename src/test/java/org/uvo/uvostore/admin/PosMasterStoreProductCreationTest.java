package org.uvo.uvostore.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;
import org.uvo.uvostore.support.WithPosIntegration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POS-2. Una tienda con UvoPOS como maestro sigue creando productos desde el panel.
 *
 * <p>El criterio original de POS-2 pedía lo contrario, y se descartó a propósito: una tienda puede vender en la
 * web productos que no están en el POS. Un producto creado en el panel no tiene mapeo, así que es editable
 * entero; sus ventas no llegan al POS y disparan la alerta de cobertura de {@code PosOrderNotifier}. Este test
 * fija la decisión para que no se "corrija" después sin decidirlo de nuevo.
 */
@WithPosIntegration
class PosMasterStoreProductCreationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("Con la integración POS encendida para la tienda, el panel sigue creando productos")
    void aPosMasterStoreCanStillCreateProducts() throws Exception {
        Store store = createStore("pos-master");
        setSetting(store, "pos_sync_enabled", "true");
        String token = loginAdmin(store, createAdmin(store, "pos-master"));

        mockMvc.perform(multipart("/api/admin/products")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .param("productType", "simple")
                        .param("name", "Producto solo web")
                        .param("active", "true")
                        .param("isFeatured", "false")
                        .param("isNew", "false")
                        .param("sortOrder", "0")
                        .param("isOnSale", "false")
                        .param("sku", "WEB-" + nextSeq())
                        .param("price", "4990")
                        .param("stock", "3")
                        .param("manageStock", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Producto solo web"));
    }
}
