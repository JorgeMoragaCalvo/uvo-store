package org.uvo.uvostore.pos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.SyncWebhookLog;
import org.uvo.uvostore.entity.pos.enums.SyncDirection;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;
import org.uvo.uvostore.entity.pos.enums.WebhookStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.service.pos.PosWebhookService;
import org.uvo.uvostore.service.pos.StockUpdatePayload;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F13. El POS no puede borrar una venta web con su valor absoluto.
 *
 * <p>El webhook de stock hacía {@code setStock(absoluto)} + {@code save}: el POS leía 5, entraba una venta
 * que dejaba 4, llegaba el absoluto 5 y el stock volvía a 5. Se podía vender otra vez algo que ya no
 * estaba. La venta sí usa un UPDATE condicional, así que la carrera la perdía siempre el descuento.
 *
 * <p>La autoridad no se decide aquí: ya estaba decidida en la otra punta de la integración
 * ({@code PosOrderNotifier.checkStockDivergence}, que ante un desacuerdo registra la divergencia en vez de
 * pisar el stock). Lo que hacía falta era que la entrada respetara lo mismo.
 */
class PosStockRaceTest extends IntegrationTestSupport {

    @Autowired
    private PosWebhookService posWebhookService;
    @Autowired
    private PosConnectionRepository connectionRepository;
    @Autowired
    private ProductSyncMappingRepository mappingRepository;

    @Test
    @DisplayName("Una venta entre la lectura del POS y su evento no se borra")
    void aSaleBetweenTheReadAndTheEventSurvives() {
        // El escenario exacto del hallazgo. El POS leyó 5; mientras su evento viajaba, se vendió una
        // unidad y quedaron 4. El evento llega diciendo "yo vi 5, déjalo en 5".
        Fixture fixture = syncedProduct(5);
        productRepository.decrementStock(fixture.product.getId(), 1);
        assertThat(reloadStock(fixture.product)).isEqualTo(4);

        SyncWebhookLog log = posWebhookService.handleStockUpdated(
                new StockUpdatePayload(fixture.companyId, fixture.externalId, "SKU-1", 5, 5, 5));

        assertThat(reloadStock(fixture.product))
                .as("el absoluto del POS no puede resucitar una unidad vendida")
                .isEqualTo(4);
        assertThat(log.getStatus())
                .as("y no puede decir que aplicó algo que no aplicó")
                .isNotEqualTo(WebhookStatus.SUCCESS);
        assertThat(log.getErrorMessage()).contains("Divergencia de stock con el POS");
    }

    @Test
    @DisplayName("Sin nada por medio, el stock del POS sí entra")
    void withoutAConcurrentSaleThePosValueIsApplied() {
        // El control positivo: el webhook sigue sirviendo para lo que existe, que es que el POS mueva el
        // inventario web (una recepción de mercadería, una venta en el mostrador).
        Fixture fixture = syncedProduct(5);

        SyncWebhookLog log = posWebhookService.handleStockUpdated(
                new StockUpdatePayload(fixture.companyId, fixture.externalId, "SKU-1", 5, 9, 9));

        assertThat(reloadStock(fixture.product)).isEqualTo(9);
        assertThat(log.getStatus()).isEqualTo(WebhookStatus.SUCCESS);
    }

    @Test
    @DisplayName("Un evento reenviado fuera de orden tampoco pisa")
    void aReplayedEventDoesNotOverwrite() {
        // Mensajes fuera de orden, el otro caso que pide probar el hallazgo: el mismo evento llega dos
        // veces y el segundo ya no encaja con el estado actual.
        Fixture fixture = syncedProduct(5);

        posWebhookService.handleStockUpdated(
                new StockUpdatePayload(fixture.companyId, fixture.externalId, "SKU-1", 5, 3, 3));
        assertThat(reloadStock(fixture.product)).isEqualTo(3);

        SyncWebhookLog replay = posWebhookService.handleStockUpdated(
                new StockUpdatePayload(fixture.companyId, fixture.externalId, "SKU-1", 5, 3, 3));

        assertThat(reloadStock(fixture.product)).isEqualTo(3);
        assertThat(replay.getStatus()).isNotEqualTo(WebhookStatus.SUCCESS);
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private record Fixture(Product product, long companyId, long externalId) {
    }

    private Fixture syncedProduct(int stock) {
        Store store = createStore("pos-race");
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto POS",
                BigDecimal.valueOf(9990));
        product.setStock(stock);
        productRepository.save(product);

        long companyId = nextSeq();
        PosConnection connection = new PosConnection();
        connection.setStore(store);
        connection.setCompanyName("Empresa POS");
        connection.setCompanyId(companyId);
        connection.setApiUrl("https://pos.test.local/api");
        connection.setApiKey("k");
        connection.setWebhookSecret("s");
        connection.setActive(true);
        connectionRepository.save(connection);

        long externalId = nextSeq();
        ProductSyncMapping mapping = new ProductSyncMapping();
        mapping.setProduct(product);
        mapping.setExternalId(externalId);
        mapping.setExternalSku(product.getSku());
        mapping.setCompanyId(companyId);
        mapping.setSyncStatus(SyncStatus.ACTIVE);
        mapping.setSyncDirection(SyncDirection.FROM_POS);
        mapping.setSyncStock(true);
        mapping.setSyncPrice(true);
        mapping.setSyncName(false);
        mapping.setSyncDescription(false);
        mapping.setLastSyncedAt(Instant.now());
        mappingRepository.save(mapping);

        return new Fixture(product, companyId, externalId);
    }

    private int reloadStock(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStock();
    }
}
