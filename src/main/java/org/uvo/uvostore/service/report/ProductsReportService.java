package org.uvo.uvostore.service.report;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

// Ports Admin\Reports\ProductsReport + ReportController::exportProducts(). Only paid orders count.
public interface ProductsReportService {
    ProductsSummaryDto getSummary(Instant start, Instant end, Long categoryId, String search);
    Page<ProductReportRowDto> getProductsData(Instant start, Instant end, Long categoryId, String search,
                                               String sortBy, String sortDirection, Pageable pageable);
    List<ProductReportRowDto> getTopByRevenue(Instant start, Instant end);
    List<ProductReportRowDto> getTopByQuantity(Instant start, Instant end);
    List<CategoryRevenueDto> getSalesByCategory(Instant start, Instant end);
    byte[] exportCsv(Instant start, Instant end, Long categoryId, String search);
}
