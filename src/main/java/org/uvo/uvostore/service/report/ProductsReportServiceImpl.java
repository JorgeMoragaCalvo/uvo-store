package org.uvo.uvostore.service.report;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.repository.OrderRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ProductsReportServiceImpl implements ProductsReportService {

    private final OrderRepository orderRepository;

    public ProductsReportServiceImpl(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductsSummaryDto getSummary(Instant start, Instant end, Long categoryId, String search) {
        List<ProductReportRowDto> rows = aggregate(start, end, categoryId, search);
        BigDecimal totalRevenue = rows.stream().map(ProductReportRowDto::totalRevenue).reduce(BigDecimal.ZERO, BigDecimal::add);
        long totalQuantity = rows.stream().mapToLong(ProductReportRowDto::totalQuantity).sum();
        BigDecimal averagePrice = totalQuantity > 0
                ? totalRevenue.divide(BigDecimal.valueOf(totalQuantity), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return new ProductsSummaryDto(totalRevenue, totalQuantity, rows.size(), averagePrice);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductReportRowDto> getProductsData(Instant start, Instant end, Long categoryId, String search,
                                                       String sortBy, String sortDirection, Pageable pageable) {
        List<ProductReportRowDto> rows = new ArrayList<>(aggregate(start, end, categoryId, search));

        Comparator<ProductReportRowDto> comparator = switch (sortBy == null ? "revenue" : sortBy) {
            case "quantity" -> Comparator.comparing(ProductReportRowDto::totalQuantity);
            case "orders" -> Comparator.comparing(ProductReportRowDto::totalOrders);
            case "name" -> Comparator.comparing(ProductReportRowDto::name);
            default -> Comparator.comparing(ProductReportRowDto::totalRevenue);
        };
        if (!"asc".equalsIgnoreCase(sortDirection)) {
            comparator = comparator.reversed();
        }
        rows.sort(comparator);

        int from = (int) pageable.getOffset();
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        List<ProductReportRowDto> pageContent = from >= rows.size() ? List.of() : rows.subList(from, to);
        return new PageImpl<>(pageContent, pageable, rows.size());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductReportRowDto> getTopByRevenue(Instant start, Instant end) {
        return aggregate(start, end, null, null).stream()
                .sorted(Comparator.comparing(ProductReportRowDto::totalRevenue).reversed())
                .limit(10)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductReportRowDto> getTopByQuantity(Instant start, Instant end) {
        return aggregate(start, end, null, null).stream()
                .sorted(Comparator.comparing(ProductReportRowDto::totalQuantity).reversed())
                .limit(10)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryRevenueDto> getSalesByCategory(Instant start, Instant end) {
        List<Order> paidOrders = paidOrders(start, end);
        Map<Long, CategoryAcc> acc = new LinkedHashMap<>();
        Map<Long, Set<Long>> productsByCategory = new LinkedHashMap<>();

        for (Order order : paidOrders) {
            for (OrderItem item : order.getItems()) {
                Product product = item.getProduct();
                Category category = product.getCategory();
                Long categoryId = category != null ? category.getId() : -1L;
                String categoryName = category != null ? category.getName() : "Sin categoría";

                CategoryAcc a = acc.computeIfAbsent(categoryId, id -> new CategoryAcc(categoryName));
                a.quantity += item.getQuantity();
                a.revenue = a.revenue.add(item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
                productsByCategory.computeIfAbsent(categoryId, id -> new HashSet<>()).add(product.getId());
            }
        }

        return acc.entrySet().stream()
                .map(e -> new CategoryRevenueDto(
                        e.getKey() == -1L ? null : e.getKey(), e.getValue().name,
                        productsByCategory.get(e.getKey()).size(), e.getValue().quantity, e.getValue().revenue))
                .sorted(Comparator.comparing(CategoryRevenueDto::totalRevenue).reversed())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] exportCsv(Instant start, Instant end, Long categoryId, String search) {
        List<ProductReportRowDto> rows = aggregate(start, end, categoryId, search).stream()
                .sorted(Comparator.comparing(ProductReportRowDto::totalRevenue).reversed())
                .toList();

        CsvBuilder csv = new CsvBuilder().header(
                "SKU", "Producto", "Categoría", "Stock Actual", "Unidades Vendidas", "Órdenes", "Ingresos Totales", "Precio Promedio");
        for (ProductReportRowDto row : rows) {
            csv.row(row.sku(), row.name(), row.categoryName() == null ? "Sin categoría" : row.categoryName(),
                    row.stock(), row.totalQuantity(), row.totalOrders(),
                    CsvBuilder.formatAmount(row.totalRevenue()), CsvBuilder.formatAmount(row.averagePrice()));
        }
        return csv.build();
    }

    // Aggregates order_items for paid orders in range into one row per product, applying the
    // category/search filters (Laravel does this in the same SQL query) during accumulation.
    private List<ProductReportRowDto> aggregate(Instant start, Instant end, Long categoryId, String search) {
        List<Order> paidOrders = paidOrders(start, end);
        Map<Long, ProductAcc> acc = new LinkedHashMap<>();

        for (Order order : paidOrders) {
            for (OrderItem item : order.getItems()) {
                Product product = item.getProduct();
                if (categoryId != null && (product.getCategory() == null || !categoryId.equals(product.getCategory().getId()))) {
                    continue;
                }
                if (search != null && !search.isBlank()) {
                    String term = search.toLowerCase();
                    boolean matches = product.getName().toLowerCase().contains(term)
                            || (product.getSku() != null && product.getSku().toLowerCase().contains(term));
                    if (!matches) {
                        continue;
                    }
                }

                ProductAcc a = acc.computeIfAbsent(product.getId(), id -> new ProductAcc(product));
                a.quantity += item.getQuantity();
                a.revenue = a.revenue.add(item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
                a.orderIds.add(order.getId());
                a.priceSum = a.priceSum.add(item.getPrice());
                a.priceCount++;
            }
        }

        return acc.values().stream()
                .map(a -> new ProductReportRowDto(
                        a.product.getId(), a.product.getName(), a.product.getSku(), a.product.getPrice(), a.product.getStock(),
                        a.product.getCategory() != null ? a.product.getCategory().getName() : null,
                        a.quantity, a.revenue, a.orderIds.size(),
                        a.priceCount > 0 ? a.priceSum.divide(BigDecimal.valueOf(a.priceCount), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO
                ))
                .toList();
    }

    private List<Order> paidOrders(Instant start, Instant end) {
        return orderRepository.findByCreatedAtBetween(start, end).stream()
                .filter(o -> o.getPaymentStatus() == PaymentStatus.PAID)
                .toList();
    }

    private static final class ProductAcc {
        final Product product;
        long quantity;
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal priceSum = BigDecimal.ZERO;
        int priceCount;
        final Set<Long> orderIds = new HashSet<>();

        ProductAcc(Product product) {
            this.product = product;
        }
    }

    private static final class CategoryAcc {
        final String name;
        long quantity;
        BigDecimal revenue = BigDecimal.ZERO;

        CategoryAcc(String name) {
            this.name = name;
        }
    }
}
