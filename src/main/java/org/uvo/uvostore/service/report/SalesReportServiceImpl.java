package org.uvo.uvostore.service.report;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.security.TenantContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SalesReportServiceImpl implements SalesReportService {

    private final OrderRepository orderRepository;
    private final ReportRevenue reportRevenue;
    private final ReportZone reportZone;

    public SalesReportServiceImpl(OrderRepository orderRepository, ReportRevenue reportRevenue, ReportZone reportZone) {
        this.orderRepository = orderRepository;
        this.reportRevenue = reportRevenue;
        this.reportZone = reportZone;
    }

    @Override
    @Transactional(readOnly = true)
    public SalesSummaryDto getSummary(Instant start, Instant end, String paymentStatus) {
        List<Order> orders = ordersInRange(start, end, paymentStatus);

        // F20: las tres cifras. El bruto es lo que se cobró, y el neto lo que quedó después de los
        // reembolsos; antes solo se informaba el bruto llamándolo "ingresos totales".
        ReportRevenue.Totals totals = reportRevenue.totals(orders);
        long totalItems = orders.stream().flatMap(o -> o.getItems().stream()).mapToLong(OrderItem::getQuantity).sum();
        long paid = orders.stream().filter(o -> o.getPaymentStatus() == PaymentStatus.PAID).count();
        long pending = orders.stream().filter(o -> o.getPaymentStatus() == PaymentStatus.PENDING).count();
        long failed = orders.stream().filter(o -> o.getPaymentStatus() == PaymentStatus.FAILED).count();
        // El ticket promedio sale del neto: es lo que dejó cada venta, no lo que se facturó antes de
        // devolver parte.
        BigDecimal avg = paid > 0 ? totals.net().divide(BigDecimal.valueOf(paid), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        return new SalesSummaryDto(orders.size(), totals.net(), totals.gross(), totals.refunded(),
                totalItems, avg, paid, pending, failed);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SalesByDayDto> getSalesByDay(Instant start, Instant end, String paymentStatus) {
        List<Order> orders = ordersInRange(start, end, paymentStatus);

        Map<String, List<Order>> byDay = orders.stream()
                // F20: el día en la zona del informe, no en UTC — ver ReportZone.
                .collect(java.util.stream.Collectors.groupingBy(o -> reportZone.dateKey(o.getCreatedAt()),
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));

        return byDay.entrySet().stream()
                .map(e -> new SalesByDayDto(
                        e.getKey(),
                        e.getValue().size(),
                        reportRevenue.totals(e.getValue()).net(),
                        e.getValue().stream().filter(o -> o.getPaymentStatus() == PaymentStatus.PAID).count()
                ))
                .sorted(Comparator.comparing(SalesByDayDto::date).reversed())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TopProductDto> getTopProducts(Instant start, Instant end) {
        List<Order> paidOrders = paidOrdersInRange(start, end);
        Map<Long, BigDecimal> refundsByOrder = reportRevenue.refundedByOrder(paidOrders);

        Map<Long, TopProductAcc> acc = new LinkedHashMap<>();
        for (Order order : paidOrders) {
            // F20: lo que de verdad dejó cada línea. Antes era precio × cantidad, que ignora el descuento
            // del cupón y los reembolsos, así que este informe no sumaba el de ventas.
            Map<Long, BigDecimal> netByProduct = reportRevenue.netByItem(
                    order, refundsByOrder.getOrDefault(order.getId(), BigDecimal.ZERO));
            for (OrderItem item : order.getItems()) {
                Long productId = item.getProduct().getId();
                TopProductAcc a = acc.computeIfAbsent(productId, id -> new TopProductAcc(item.getProduct().getName()));
                a.quantity += item.getQuantity();
                a.orderIds.add(order.getId());
            }
            netByProduct.forEach((productId, net) -> {
                TopProductAcc a = acc.get(productId);
                if (a != null) {
                    a.revenue = a.revenue.add(net);
                }
            });
        }

        return acc.entrySet().stream()
                .map(e -> new TopProductDto(e.getKey(), e.getValue().name, e.getValue().quantity, e.getValue().revenue, e.getValue().orderIds.size()))
                .sorted(Comparator.comparing(TopProductDto::totalRevenue).reversed())
                .limit(10)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentMethodRevenueDto> getSalesByPaymentMethod(Instant start, Instant end) {
        List<Order> paidOrders = paidOrdersInRange(start, end);

        Map<String, List<Order>> byMethod = paidOrders.stream()
                .collect(java.util.stream.Collectors.groupingBy(o -> o.getPaymentMethod() == null ? "No especificado" : o.getPaymentMethod().name()));

        return byMethod.entrySet().stream()
                .map(e -> new PaymentMethodRevenueDto(e.getKey(), e.getValue().size(), reportRevenue.totals(e.getValue()).net()))
                .sorted(Comparator.comparing(PaymentMethodRevenueDto::totalRevenue).reversed())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] exportCsv(Instant start, Instant end, String paymentStatus) {
        List<SalesByDayDto> rows = getSalesByDay(start, end, paymentStatus);
        CsvBuilder csv = new CsvBuilder().header("Fecha", "Total Órdenes", "Órdenes Pagadas", "Ingresos netos", "Ticket Promedio");
        for (SalesByDayDto row : rows) {
            BigDecimal avgTicket = row.paidOrders() > 0
                    ? row.revenue().divide(BigDecimal.valueOf(row.paidOrders()), 0, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            csv.row(
                    LocalDate.parse(row.date()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                    row.ordersCount(), row.paidOrders(), CsvBuilder.formatAmount(row.revenue()), CsvBuilder.formatAmount(avgTicket)
            );
        }
        return csv.build();
    }

    private List<Order> ordersInRange(Instant start, Instant end, String paymentStatus) {
        List<Order> orders = orderRepository
                .findByStoreIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(TenantContext.requireStoreId(), start, end);
        if (paymentStatus == null || paymentStatus.isBlank() || "all".equalsIgnoreCase(paymentStatus)) {
            return orders;
        }
        PaymentStatus status = PaymentStatus.valueOf(paymentStatus.toUpperCase());
        return orders.stream().filter(o -> o.getPaymentStatus() == status).toList();
    }

    private List<Order> paidOrdersInRange(Instant start, Instant end) {
        return orderRepository
                .findByStoreIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(TenantContext.requireStoreId(), start, end)
                .stream()
                .filter(o -> o.getPaymentStatus() == PaymentStatus.PAID)
                .toList();
    }

    private static final class TopProductAcc {
        final String name;
        long quantity;
        BigDecimal revenue = BigDecimal.ZERO;
        final java.util.Set<Long> orderIds = new java.util.HashSet<>();

        TopProductAcc(String name) {
            this.name = name;
        }
    }
}
