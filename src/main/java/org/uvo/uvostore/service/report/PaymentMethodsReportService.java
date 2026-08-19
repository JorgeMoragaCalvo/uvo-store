package org.uvo.uvostore.service.report;

import java.time.Instant;
import java.util.List;

// Ports Admin\Reports\PaymentMethodsReport + ReportController::exportPaymentMethods().
// The "chart over time" data (getChartDataProperty) is not ported — it's pure visualization
// sugar (per-day-per-method revenue series) with no other consumer, low value for the effort.
public interface PaymentMethodsReportService {
    PaymentMethodsSummaryDto getSummary(Instant start, Instant end, String paymentStatus);
    List<PaymentMethodDetailDto> getByPaymentMethod(Instant start, Instant end, String paymentStatus);
    List<PaymentStatusDistributionDto> getStatusDistribution(Instant start, Instant end);
    byte[] exportCsv(Instant start, Instant end, String paymentStatus);
}
