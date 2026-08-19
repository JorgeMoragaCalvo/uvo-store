package org.uvo.uvostore.service.report;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.repository.OrderRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SalesReportServiceImpl implements SalesReportService {

    private final OrderRepository orderRepository;

    public SalesReportServiceImpl(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public SalesSummaryDto getSummary(Instant start, Instant end, String paymentStatus) {
        List<Order> orders = ordersInRange(start, end, paymentStatus);

        BigDecimal totalRevenue = sumPaid(orders);
        long totalItems = orders.stream().flatMap(o -> o.getItems().stream()).mapToLong(OrderItem::getQuantity).sum();
        long paid = orders.stream().filter(o -> o.getPaymentStatus() == PaymentStatus.PAID).count();
        long pending = orders.stream().filter(o -> o.getPaymentStatus() == PaymentStatus.PENDING).count();
        long failed = orders.stream().filter(o -> o.getPaymentStatus() == PaymentStatus.FAILED).count();
        BigDecimal avg = paid > 0 ? totalRevenue.divide(BigDecimal.valueOf(paid), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        return new SalesSummaryDto(orders.size(), totalRevenue, totalItems, avg, paid, pending, failed);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SalesByDayDto> getSalesByDay(Instant start, Instant end, String paymentStatus) {
        List<Order> orders = ordersInRange(start, end, paymentStatus);

        Map<String, List<Order>> byDay = orders.stream()
                .collect(java.util.stream.Collectors.groupingBy(o -> dateKey(o.getCreatedAt()), LinkedHashMap::new, java.util.stream.Collectors.toList()));

        return byDay.entrySet().stream()
                .map(e -> new SalesByDayDto(
                        e.getKey(),
                        e.getValue().size(),
                        sumPaid(e.getValue()),
                        e.getValue().stream().filter(o -> o.getPaymentStatus() == PaymentStatus.PAID).count()
                ))
                .sorted(Comparator.comparing(SalesByDayDto::date).reversed())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TopProductDto> getTopProducts(Instant start, Instant end) {
        List<Order> paidOrders = orderRepository.findByCreatedAtBetween(start, end).stream()
                .filter(o -> o.getPaymentStatus() == PaymentStatus.PAID)
                .toList();

        Map<Long, TopProductAcc> acc = new LinkedHashMap<>();
        for (Order order : paidOrders) {
            for (OrderItem item : order.getItems()) {
                TopProductAcc a = acc.computeIfAbsent(item.getProduct().getId(), id -> new TopProductAcc(item.getProduct().getName()));
                a.quantity += item.getQuantity();
                a.revenue = a.revenue.add(item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
                a.orderIds.add(order.getId());
            }
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
        List<Order> paidOrders = orderRepository.findByCreatedAtBetween(start, end).stream()
                .filter(o -> o.getPaymentStatus() == PaymentStatus.PAID)
                .toList();

        Map<String, List<Order>> byMethod = paidOrders.stream()
                .collect(java.util.stream.Collectors.groupingBy(o -> o.getPaymentMethod() == null ? "No especificado" : o.getPaymentMethod()));

        return byMethod.entrySet().stream()
                .map(e -> new PaymentMethodRevenueDto(e.getKey(), e.getValue().size(), sumPaid(e.getValue())))
                .sorted(Comparator.comparing(PaymentMethodRevenueDto::totalRevenue).reversed())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] exportCsv(Instant start, Instant end, String paymentStatus) {
        List<SalesByDayDto> rows = getSalesByDay(start, end, paymentStatus);
        CsvBuilder csv = new CsvBuilder().header("Fecha", "Total Órdenes", "Órdenes Pagadas", "Ingresos", "Ticket Promedio");
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
        List<Order> orders = orderRepository.findByCreatedAtBetween(start, end);
        if (paymentStatus == null || paymentStatus.isBlank() || "all".equalsIgnoreCase(paymentStatus)) {
            return orders;
        }
        PaymentStatus status = PaymentStatus.valueOf(paymentStatus.toUpperCase());
        return orders.stream().filter(o -> o.getPaymentStatus() == status).toList();
    }

    private BigDecimal sumPaid(List<Order> orders) {
        return orders.stream()
                .filter(o -> o.getPaymentStatus() == PaymentStatus.PAID)
                .map(Order::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String dateKey(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate().toString();
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
