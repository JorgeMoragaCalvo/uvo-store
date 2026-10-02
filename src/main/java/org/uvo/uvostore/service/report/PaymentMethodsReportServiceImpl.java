package org.uvo.uvostore.service.report;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.security.TenantContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PaymentMethodsReportServiceImpl implements PaymentMethodsReportService {

    private final OrderRepository orderRepository;
    private final ReportRevenue reportRevenue;

    public PaymentMethodsReportServiceImpl(OrderRepository orderRepository, ReportRevenue reportRevenue) {
        this.orderRepository = orderRepository;
        this.reportRevenue = reportRevenue;
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentMethodsSummaryDto getSummary(Instant start, Instant end, String paymentStatus) {
        List<Order> orders = ordersInRange(start, end, paymentStatus);
        BigDecimal totalRevenue = reportRevenue.totals(orders).net();
        long paid = orders.stream().filter(o -> o.getPaymentStatus() == PaymentStatus.PAID).count();
        // F20: pendientes y fallidas salen de UNA consulta, no de dos más. Este método pedía tres veces
        // el mismo rango de órdenes para contar tres estados.
        List<Order> allInRange = ordersInRange(start, end, null);
        long pending = allInRange.stream().filter(o -> o.getPaymentStatus() == PaymentStatus.PENDING).count();
        long failed = allInRange.stream().filter(o -> o.getPaymentStatus() == PaymentStatus.FAILED).count();
        return new PaymentMethodsSummaryDto(orders.size(), totalRevenue, paid, pending, failed);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentMethodDetailDto> getByPaymentMethod(Instant start, Instant end, String paymentStatus) {
        List<Order> orders = ordersInRange(start, end, paymentStatus);

        Map<String, List<Order>> byMethod = orders.stream()
                .collect(java.util.stream.Collectors.groupingBy(o -> o.getPaymentMethod() == null ? "No especificado" : o.getPaymentMethod().name(),
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));

        return byMethod.entrySet().stream()
                .map(e -> {
                    List<Order> methodOrders = e.getValue();
                    long paid = methodOrders.stream().filter(o -> o.getPaymentStatus() == PaymentStatus.PAID).count();
                    long pending = methodOrders.stream().filter(o -> o.getPaymentStatus() == PaymentStatus.PENDING).count();
                    long failed = methodOrders.stream().filter(o -> o.getPaymentStatus() == PaymentStatus.FAILED).count();
                    BigDecimal revenue = reportRevenue.totals(methodOrders).net();
                    BigDecimal avg = paid > 0 ? revenue.divide(BigDecimal.valueOf(paid), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
                    double successRate = methodOrders.size() > 0 ? (paid * 100.0) / methodOrders.size() : 0;
                    return new PaymentMethodDetailDto(e.getKey(), methodOrders.size(), revenue, paid, pending, failed, avg, successRate);
                })
                .sorted(Comparator.comparing(PaymentMethodDetailDto::totalRevenue).reversed())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentStatusDistributionDto> getStatusDistribution(Instant start, Instant end) {
        List<Order> orders = ordersInRange(start, end, null);
        Map<PaymentStatus, List<Order>> byStatus = orders.stream()
                .collect(java.util.stream.Collectors.groupingBy(Order::getPaymentStatus, LinkedHashMap::new, java.util.stream.Collectors.toList()));

        return byStatus.entrySet().stream()
                .map(e -> new PaymentStatusDistributionDto(
                        e.getKey().name().toLowerCase(), e.getValue().size(),
                        e.getValue().stream().map(Order::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] exportCsv(Instant start, Instant end, String paymentStatus) {
        List<PaymentMethodDetailDto> rows = getByPaymentMethod(start, end, paymentStatus);
        CsvBuilder csv = new CsvBuilder().header(
                "Método de Pago", "Total Órdenes", "Órdenes Pagadas", "Órdenes Pendientes", "Órdenes Fallidas",
                "Ingresos Totales", "Ticket Promedio", "Tasa de Éxito (%)");
        for (PaymentMethodDetailDto row : rows) {
            csv.row(row.paymentMethod(), row.ordersCount(), row.paidOrders(), row.pendingOrders(), row.failedOrders(),
                    CsvBuilder.formatAmount(row.totalRevenue()), CsvBuilder.formatAmount(row.averageOrderValue()),
                    String.format("%.1f", row.successRate()));
        }
        return csv.build();
    }

    private List<Order> ordersInRange(Instant start, Instant end, String paymentStatus) {
        List<Order> orders = orderRepository
                .findByStoreIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(TenantContext.requireStoreId(), start, end);
        if ("paid".equalsIgnoreCase(paymentStatus)) {
            return orders.stream().filter(o -> o.getPaymentStatus() == PaymentStatus.PAID).toList();
        }
        return orders;
    }
}
