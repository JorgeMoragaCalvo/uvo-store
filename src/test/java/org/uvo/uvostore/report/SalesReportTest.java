package org.uvo.uvostore.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.order.enums.RefundStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.service.report.PaymentMethodRevenueDto;
import org.uvo.uvostore.service.report.SalesByDayDto;
import org.uvo.uvostore.service.report.SalesReportService;
import org.uvo.uvostore.service.report.SalesSummaryDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F20. Los informes informan el dinero que quedó, no el que se facturó.
 *
 * <p>Un reembolso parcial <b>deja la orden en PAID</b> a propósito —la compra no se deshace— y los tres
 * informes sumaban {@code Order.total} de las órdenes pagadas. Así que el dinero devuelto seguía contando
 * como ingreso, sin excepción ni alerta: la cifra simplemente salía más alta que la real.
 */
class SalesReportTest extends ReportTestSupport {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");

    @Autowired
    private SalesReportService salesReportService;

    @Test
    @DisplayName("Un reembolso parcial baja los ingresos y queda visible aparte")
    void aPartialRefundLowersRevenueAndIsReported() throws Exception {
        Store store = createStore("rep-partial");
        Product product = product(store);
        Instant when = at(2026, 6, 15, 12, 0);

        Order order = paidOrder(store, product, BigDecimal.valueOf(100000), when);
        refund(order, BigDecimal.valueOf(30000), RefundStatus.COMPLETED);

        // La orden sigue pagada: el reembolso parcial no la deshace.
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.PAID);

        SalesSummaryDto summary = inStore(store, () -> salesReportService.getSummary(
                startOfDay(2026, 6, 15), startOfDay(2026, 6, 16), "all"));

        assertThat(summary.grossRevenue()).isEqualByComparingTo("100000");
        assertThat(summary.refundedAmount()).isEqualByComparingTo("30000");
        // Esto decía 100.000.
        assertThat(summary.totalRevenue()).isEqualByComparingTo("70000");
        // Y el ticket promedio sale del neto, no del bruto.
        assertThat(summary.averageOrderValue()).isEqualByComparingTo("70000.00");

        List<SalesByDayDto> byDay = inStore(store, () -> salesReportService.getSalesByDay(
                startOfDay(2026, 6, 15), startOfDay(2026, 6, 16), "all"));
        assertThat(byDay).singleElement()
                .satisfies(row -> assertThat(row.revenue()).isEqualByComparingTo("70000"));

        List<PaymentMethodRevenueDto> byMethod = inStore(store, () -> salesReportService.getSalesByPaymentMethod(
                startOfDay(2026, 6, 15), startOfDay(2026, 6, 16)));
        assertThat(byMethod).singleElement()
                .satisfies(row -> assertThat(row.totalRevenue()).isEqualByComparingTo("70000"));
    }

    @Test
    @DisplayName("Varios reembolsos parciales se acumulan")
    void severalPartialRefundsAddUp() throws Exception {
        Store store = createStore("rep-several");
        Product product = product(store);
        Order order = paidOrder(store, product, BigDecimal.valueOf(100000), at(2026, 6, 15, 12, 0));
        refund(order, BigDecimal.valueOf(20000), RefundStatus.COMPLETED);
        refund(order, BigDecimal.valueOf(15000), RefundStatus.COMPLETED);

        SalesSummaryDto summary = inStore(store, () -> salesReportService.getSummary(
                startOfDay(2026, 6, 15), startOfDay(2026, 6, 16), "all"));

        assertThat(summary.refundedAmount()).isEqualByComparingTo("35000");
        assertThat(summary.totalRevenue()).isEqualByComparingTo("65000");
    }

    @Test
    @DisplayName("Un reembolso rechazado por la pasarela no resta nada")
    void aFailedRefundSubtractsNothing() throws Exception {
        Store store = createStore("rep-failed");
        Product product = product(store);
        Order order = paidOrder(store, product, BigDecimal.valueOf(100000), at(2026, 6, 15, 12, 0));
        // FAILED = la pasarela rechazó, no se movió dinero. Misma regla que ya aplica totalRefunded.
        refund(order, BigDecimal.valueOf(40000), RefundStatus.FAILED);

        SalesSummaryDto summary = inStore(store, () -> salesReportService.getSummary(
                startOfDay(2026, 6, 15), startOfDay(2026, 6, 16), "all"));

        assertThat(summary.refundedAmount()).isEqualByComparingTo("0");
        assertThat(summary.totalRevenue()).isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("Una intención de reembolso PENDING ya resta: el dinero puede estar fuera")
    void aPendingRefundAlreadySubtracts() throws Exception {
        Store store = createStore("rep-pending");
        Product product = product(store);
        Order order = paidOrder(store, product, BigDecimal.valueOf(100000), at(2026, 6, 15, 12, 0));
        // F12 abre la intención antes de llamar a la pasarela: si quedó PENDING, el dinero pudo salir y
        // no se sabe. El informe prefiere no contarlo como ingreso.
        refund(order, BigDecimal.valueOf(25000), RefundStatus.PENDING);

        SalesSummaryDto summary = inStore(store, () -> salesReportService.getSummary(
                startOfDay(2026, 6, 15), startOfDay(2026, 6, 16), "all"));

        assertThat(summary.totalRevenue()).isEqualByComparingTo("75000");
    }

    @Test
    @DisplayName("Una orden sin reembolsos informa lo mismo en bruto y en neto")
    void anUnrefundedOrderReportsTheSameBothWays() throws Exception {
        Store store = createStore("rep-clean");
        Product product = product(store);
        paidOrder(store, product, BigDecimal.valueOf(49990), at(2026, 6, 15, 12, 0));

        SalesSummaryDto summary = inStore(store, () -> salesReportService.getSummary(
                startOfDay(2026, 6, 15), startOfDay(2026, 6, 16), "all"));

        assertThat(summary.grossRevenue()).isEqualByComparingTo("49990");
        assertThat(summary.totalRevenue()).isEqualByComparingTo("49990");
        assertThat(summary.refundedAmount()).isEqualByComparingTo("0");
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private Product product(Store store) {
        return createProduct(store, createCategory(store, "Informes"), "Producto", BigDecimal.valueOf(100000));
    }

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, CHILE).toInstant();
    }

    private static Instant startOfDay(int year, int month, int day) {
        return ZonedDateTime.of(year, month, day, 0, 0, 0, 0, CHILE).toInstant();
    }
}
