package org.uvo.uvostore.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.RefundStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.service.report.CategoryRevenueDto;
import org.uvo.uvostore.service.report.ProductReportRowDto;
import org.uvo.uvostore.service.report.ProductsReportService;
import org.uvo.uvostore.service.report.SalesReportService;
import org.uvo.uvostore.service.report.TopProductDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F20. El ingreso por producto es lo que el cliente pagó por él, no su precio de lista.
 *
 * <p>Los informes por producto y por categoría sumaban {@code precio × cantidad}, y
 * <b>{@code OrderItem} no tiene columna de descuento</b>: el del cupón vive solo en
 * {@code Order.discountAmount}. Así que un pedido con un cupón de 10.000 aparecía en el informe de
 * productos por 10.000 más de lo que se cobró, y ese informe no sumaba el de ventas.
 *
 * <p>Lo que se fija aquí es la invariante: <b>la suma de las filas por producto es el neto de la
 * orden</b>. Es la misma regla que F06 dejó escrita para el IVA, y por la misma razón.
 */
class ProductRevenueTest extends ReportTestSupport {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    private static final Instant WHEN = ZonedDateTime.of(2026, 6, 15, 12, 0, 0, 0, CHILE).toInstant();

    @Autowired
    private ProductsReportService productsReportService;
    @Autowired
    private SalesReportService salesReportService;

    @Test
    @DisplayName("El descuento del cupón se reparte, y las filas suman el neto de la orden")
    void theCouponDiscountIsSpreadAcrossTheLines() {
        Store store = createStore("prod-discount");
        Category category = createCategory(store, "Informes");
        Product cheap = createProduct(store, category, "Barato", BigDecimal.valueOf(20000));
        Product pricey = createProduct(store, category, "Caro", BigDecimal.valueOf(30000));

        // Subtotal 50.000, cupón de 10.000, total 40.000.
        paidOrder(store, List.of(new Line(cheap, 1, BigDecimal.valueOf(20000)), new Line(pricey, 1, BigDecimal.valueOf(30000))),
                BigDecimal.valueOf(40000), BigDecimal.valueOf(10000), WHEN);

        List<ProductReportRowDto> rows = inStore(store, () ->
                productsReportService.getTopByRevenue(start(), end()));

        // Antes: 20.000 + 30.000 = 50.000, diez mil más de lo que entró en caja.
        assertThat(rows).hasSize(2);
        BigDecimal sum = rows.stream().map(ProductReportRowDto::totalRevenue).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).as("las filas tienen que sumar el neto de la orden").isEqualByComparingTo("40000");

        // Proporcional: el de 30.000 carga 6.000 de descuento y el de 20.000 carga 4.000.
        assertThat(revenueOf(rows, pricey)).isEqualByComparingTo("24000");
        assertThat(revenueOf(rows, cheap)).isEqualByComparingTo("16000");
    }

    @Test
    @DisplayName("Un reparto que no es exacto sigue sumando el total, sin perder ni un peso")
    void anUnevenSplitStillAddsUpExactly() {
        Store store = createStore("prod-uneven");
        Category category = createCategory(store, "Informes");
        // 3 × 3.333 = 9.999 contra 1 × 1, para que el reparto proporcional no dé números redondos.
        Product a = createProduct(store, category, "Tres mil", BigDecimal.valueOf(3333));
        Product b = createProduct(store, category, "Uno", BigDecimal.valueOf(1));

        paidOrder(store, List.of(new Line(a, 3, BigDecimal.valueOf(3333)), new Line(b, 1, BigDecimal.ONE)),
                BigDecimal.valueOf(9000), BigDecimal.valueOf(1000), WHEN);

        List<ProductReportRowDto> rows = inStore(store, () ->
                productsReportService.getTopByRevenue(start(), end()));

        BigDecimal sum = rows.stream().map(ProductReportRowDto::totalRevenue).reduce(BigDecimal.ZERO, BigDecimal::add);
        // El resto va a la última línea: si cada parte se redondeara por su cuenta, esto fallaría por
        // unos pesos y el informe por producto dejaría de cuadrar con el de ventas.
        assertThat(sum).isEqualByComparingTo("9000");
    }

    @Test
    @DisplayName("Un reembolso parcial también baja el ingreso del producto")
    void aPartialRefundLowersProductRevenueToo() {
        Store store = createStore("prod-refund");
        Category category = createCategory(store, "Informes");
        Product product = createProduct(store, category, "Producto", BigDecimal.valueOf(50000));

        Order order = paidOrder(store, product, BigDecimal.valueOf(50000), WHEN);
        refund(order, BigDecimal.valueOf(20000), RefundStatus.COMPLETED);

        List<ProductReportRowDto> rows = inStore(store, () ->
                productsReportService.getTopByRevenue(start(), end()));
        assertThat(rows).singleElement()
                .satisfies(row -> assertThat(row.totalRevenue()).isEqualByComparingTo("30000"));

        // Y el informe de categorías cuenta lo mismo.
        List<CategoryRevenueDto> categories = inStore(store, () ->
                productsReportService.getSalesByCategory(start(), end()));
        assertThat(categories).singleElement()
                .satisfies(row -> assertThat(row.totalRevenue()).isEqualByComparingTo("30000"));

        // Y los "top productos" del informe de ventas, que es el que cita el hallazgo.
        List<TopProductDto> top = inStore(store, () -> salesReportService.getTopProducts(start(), end()));
        assertThat(top).singleElement()
                .satisfies(row -> assertThat(row.totalRevenue()).isEqualByComparingTo("30000"));
    }

    @Test
    @DisplayName("Sin descuento ni reembolso, el ingreso del producto es precio por cantidad")
    void withoutDiscountsItIsJustPriceTimesQuantity() {
        Store store = createStore("prod-plain");
        Category category = createCategory(store, "Informes");
        Product product = createProduct(store, category, "Producto", BigDecimal.valueOf(9990));

        paidOrder(store, List.of(new Line(product, 3, BigDecimal.valueOf(9990))),
                BigDecimal.valueOf(29970), BigDecimal.ZERO, WHEN);

        List<ProductReportRowDto> rows = inStore(store, () ->
                productsReportService.getTopByRevenue(start(), end()));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.totalRevenue()).isEqualByComparingTo("29970");
            assertThat(row.totalQuantity()).isEqualTo(3);
        });
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private static BigDecimal revenueOf(List<ProductReportRowDto> rows, Product product) {
        return rows.stream()
                .filter(r -> r.id().equals(product.getId()))
                .findFirst()
                .orElseThrow()
                .totalRevenue();
    }

    private static Instant start() {
        return ZonedDateTime.of(2026, 6, 15, 0, 0, 0, 0, CHILE).toInstant();
    }

    private static Instant end() {
        return ZonedDateTime.of(2026, 6, 16, 0, 0, 0, 0, CHILE).toInstant();
    }
}
