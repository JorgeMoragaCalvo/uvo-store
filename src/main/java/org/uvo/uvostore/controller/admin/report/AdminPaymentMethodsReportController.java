package org.uvo.uvostore.controller.admin.report;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.report.PaymentMethodDetailDto;
import org.uvo.uvostore.service.report.PaymentMethodsReportService;
import org.uvo.uvostore.service.report.PaymentMethodsSummaryDto;
import org.uvo.uvostore.service.report.PaymentStatusDistributionDto;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin/reports/payment-methods")
public class AdminPaymentMethodsReportController {

    private final PaymentMethodsReportService paymentMethodsReportService;

    public AdminPaymentMethodsReportController(PaymentMethodsReportService paymentMethodsReportService) {
        this.paymentMethodsReportService = paymentMethodsReportService;
    }

    @GetMapping("/summary")
    public PaymentMethodsSummaryDto summary(@RequestParam LocalDate startDate, @RequestParam LocalDate endDate,
                                             @RequestParam(defaultValue = "paid") String paymentStatus) {
        return paymentMethodsReportService.getSummary(ReportDateRange.start(startDate), ReportDateRange.end(endDate), paymentStatus);
    }

    @GetMapping
    public List<PaymentMethodDetailDto> byMethod(@RequestParam LocalDate startDate, @RequestParam LocalDate endDate,
                                                  @RequestParam(defaultValue = "paid") String paymentStatus) {
        return paymentMethodsReportService.getByPaymentMethod(ReportDateRange.start(startDate), ReportDateRange.end(endDate), paymentStatus);
    }

    @GetMapping("/status-distribution")
    public List<PaymentStatusDistributionDto> statusDistribution(@RequestParam LocalDate startDate, @RequestParam LocalDate endDate) {
        return paymentMethodsReportService.getStatusDistribution(ReportDateRange.start(startDate), ReportDateRange.end(endDate));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam LocalDate startDate, @RequestParam LocalDate endDate,
                                          @RequestParam(defaultValue = "paid") String paymentStatus) {
        byte[] csv = paymentMethodsReportService.exportCsv(ReportDateRange.start(startDate), ReportDateRange.end(endDate), paymentStatus);
        String filename = "reporte_metodos_pago_" + startDate + "_" + endDate + ".csv";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(csv);
    }
}
