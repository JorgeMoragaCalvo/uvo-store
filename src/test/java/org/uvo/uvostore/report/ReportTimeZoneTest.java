package org.uvo.uvostore.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.service.report.ReportZone;
import org.uvo.uvostore.service.report.SalesByDayDto;
import org.uvo.uvostore.service.report.SalesReportService;
import org.uvo.uvostore.service.report.SalesSummaryDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F20. Una venta pertenece al día en que se hizo <b>en Chile</b>, no en UTC.
 *
 * <p>Con los dos extremos en UTC, un informe de "1 a 31 de octubre" iba en realidad del 30 de septiembre a
 * las 21:00 al 31 de octubre a las 20:59. Las ventas de la tarde del último día del mes <b>se caían de ese
 * mes y aparecían en el siguiente</b>, que es lo que hace que los totales mensuales no cuadren con nada.
 *
 * <p>Las dos mitades tienen que salir de la misma zona: el rango que delimita qué órdenes entran
 * ({@code ReportDateRange}) y la etiqueta del día de cada fila ({@code ReportZone.dateKey}).
 */
class ReportTimeZoneTest extends ReportTestSupport {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");

    @Autowired
    private SalesReportService salesReportService;
    @Autowired
    private ReportZone reportZone;

    @Test
    @DisplayName("La zona por defecto del informe es la de Chile")
    void theDefaultZoneIsChile() {
        assertThat(reportZone.zone()).isEqualTo(CHILE);
    }

    @Test
    @DisplayName("Una venta de las 22:30 del 31 de octubre es de octubre, no de noviembre")
    void aLateOctoberSaleBelongsToOctober() {
        Store store = createStore("tz-month-end");
        Product product = product(store);

        // 22:30 del 31 de octubre en Chile son las 01:30 UTC del 1 de noviembre: con el rango en UTC esta
        // venta se caía de octubre.
        Instant lateOctober = ZonedDateTime.of(2026, 10, 31, 22, 30, 0, 0, CHILE).toInstant();
        paidOrder(store, product, BigDecimal.valueOf(50000), lateOctober);

        SalesSummaryDto october = inStore(store, () -> salesReportService.getSummary(
                startOfDay(2026, 10, 1), startOfNextDay(2026, 10, 31), "all"));
        assertThat(october.totalOrders()).isEqualTo(1);
        assertThat(october.totalRevenue()).isEqualByComparingTo("50000");

        SalesSummaryDto november = inStore(store, () -> salesReportService.getSummary(
                startOfDay(2026, 11, 1), startOfNextDay(2026, 11, 30), "all"));
        assertThat(november.totalOrders()).as("no puede contarse dos veces").isZero();
    }

    @Test
    @DisplayName("Y la fila se agrupa bajo el 31 de octubre")
    void theRowIsLabelledWithTheChileanDay() {
        Store store = createStore("tz-label");
        Product product = product(store);
        paidOrder(store, product, BigDecimal.valueOf(50000),
                ZonedDateTime.of(2026, 10, 31, 22, 30, 0, 0, CHILE).toInstant());

        List<SalesByDayDto> byDay = inStore(store, () -> salesReportService.getSalesByDay(
                startOfDay(2026, 10, 1), startOfNextDay(2026, 10, 31), "all"));

        assertThat(byDay).singleElement()
                .satisfies(row -> assertThat(row.date()).isEqualTo("2026-10-31"));
    }

    @Test
    @DisplayName("Una venta de la madrugada sigue siendo del día en que ocurrió")
    void anEarlyMorningSaleStaysOnItsOwnDay() {
        Store store = createStore("tz-early");
        Product product = product(store);
        // 00:30 del 1 de noviembre en Chile son las 03:30 UTC del mismo día: este caso ya salía bien, y
        // está aquí para que el arreglo no lo mueva al revés.
        paidOrder(store, product, BigDecimal.valueOf(50000),
                ZonedDateTime.of(2026, 11, 1, 0, 30, 0, 0, CHILE).toInstant());

        SalesSummaryDto october = inStore(store, () -> salesReportService.getSummary(
                startOfDay(2026, 10, 1), startOfNextDay(2026, 10, 31), "all"));
        assertThat(october.totalOrders()).isZero();

        SalesSummaryDto november = inStore(store, () -> salesReportService.getSummary(
                startOfDay(2026, 11, 1), startOfNextDay(2026, 11, 30), "all"));
        assertThat(november.totalOrders()).isEqualTo(1);
    }

    @Test
    @DisplayName("Una venta en la última fracción de segundo del día entra en ese día")
    void aSaleInTheLastFractionOfTheDayIsIncluded() {
        Store store = createStore("tz-last-tick");
        Product product = product(store);

        // El tope anterior era 23:59:59 contra una consulta inclusiva, y Postgres guarda microsegundos:
        // esta orden no aparecía en el informe de ese día ni en el del siguiente.
        Instant lastTick = ZonedDateTime.of(2026, 10, 15, 23, 59, 59, 500_000_000, CHILE).toInstant();
        paidOrder(store, product, BigDecimal.valueOf(50000), lastTick);

        SalesSummaryDto theDay = inStore(store, () -> salesReportService.getSummary(
                startOfDay(2026, 10, 15), startOfNextDay(2026, 10, 15), "all"));

        assertThat(theDay.totalOrders()).isEqualTo(1);
        assertThat(theDay.totalRevenue()).isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("Y por HTTP, que es donde se convierte el startDate/endDate que manda el panel")
    void theRangeIsBuiltInChileFromTheRequestedDates() throws Exception {
        // Los casos de arriba llaman al servicio con instantes ya calculados, así que no pasan por
        // ReportDateRange — que es justo donde vivía la mitad peor del fallo. Este entra por el endpoint.
        Store store = createStore("tz-http");
        User admin = createAdmin(store, "tz-http");
        String token = loginAdmin(store, admin);
        Product product = product(store);
        paidOrder(store, product, BigDecimal.valueOf(50000),
                ZonedDateTime.of(2026, 10, 31, 22, 30, 0, 0, CHILE).toInstant());

        mockMvc.perform(get("/api/admin/reports/sales/summary")
                        .param("startDate", "2026-10-01").param("endDate", "2026-10-31")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOrders").value(1))
                .andExpect(jsonPath("$.totalRevenue").value(50000));

        mockMvc.perform(get("/api/admin/reports/sales/summary")
                        .param("startDate", "2026-11-01").param("endDate", "2026-11-30")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOrders").value(0));
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private Product product(Store store) {
        return createProduct(store, createCategory(store, "Informes"), "Producto", BigDecimal.valueOf(50000));
    }

    /** Lo mismo que hace ReportDateRange: inicio del día local pedido. */
    private static Instant startOfDay(int year, int month, int day) {
        return ZonedDateTime.of(year, month, day, 0, 0, 0, 0, CHILE).toInstant();
    }

    /** Y su tope exclusivo: el inicio del día siguiente. */
    private static Instant startOfNextDay(int year, int month, int day) {
        return ZonedDateTime.of(year, month, day, 0, 0, 0, 0, CHILE).plusDays(1).toInstant();
    }
}
