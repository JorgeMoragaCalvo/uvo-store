package org.uvo.uvostore.service.report;

import java.time.Instant;
import java.util.List;

// Ports Admin\Reports\SalesReport + ReportController::exportSales().
public interface SalesReportService {
    SalesSummaryDto getSummary(Instant start, Instant end, String paymentStatus);
    List<SalesByDayDto> getSalesByDay(Instant start, Instant end, String paymentStatus);
    List<TopProductDto> getTopProducts(Instant start, Instant end);
    List<PaymentMethodRevenueDto> getSalesByPaymentMethod(Instant start, Instant end);
    byte[] exportCsv(Instant start, Instant end, String paymentStatus);
}
