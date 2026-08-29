package org.uvo.uvostore.controller.admin.report;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.report.PaymentMethodRevenueDto;
import org.uvo.uvostore.service.report.SalesByDayDto;
import org.uvo.uvostore.service.report.SalesReportService;
import org.uvo.uvostore.service.report.SalesSummaryDto;
import org.uvo.uvostore.service.report.TopProductDto;

import java.time.LocalDate;
import java.util.List;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Reportes de ventas (admin)", description = "Resumen, serie diaria y desgloses de ventas, JWT bearer con rol ADMIN")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/reports/sales")
public class AdminSalesReportController {

    private final SalesReportService salesReportService;

    public AdminSalesReportController(SalesReportService salesReportService) {
        this.salesReportService = salesReportService;
    }

    @GetMapping("/summary")
    public SalesSummaryDto summary(@RequestParam LocalDate startDate, @RequestParam LocalDate endDate,
                                    @RequestParam(defaultValue = "all") String paymentStatus) {
        return salesReportService.getSummary(ReportDateRange.start(startDate), ReportDateRange.end(endDate), paymentStatus);
    }

    @GetMapping("/by-day")
    public List<SalesByDayDto> byDay(@RequestParam LocalDate startDate, @RequestParam LocalDate endDate,
                                      @RequestParam(defaultValue = "all") String paymentStatus) {
        return salesReportService.getSalesByDay(ReportDateRange.start(startDate), ReportDateRange.end(endDate), paymentStatus);
    }

    @GetMapping("/top-products")
    public List<TopProductDto> topProducts(@RequestParam LocalDate startDate, @RequestParam LocalDate endDate) {
        return salesReportService.getTopProducts(ReportDateRange.start(startDate), ReportDateRange.end(endDate));
    }

    @GetMapping("/by-payment-method")
    public List<PaymentMethodRevenueDto> byPaymentMethod(@RequestParam LocalDate startDate, @RequestParam LocalDate endDate) {
        return salesReportService.getSalesByPaymentMethod(ReportDateRange.start(startDate), ReportDateRange.end(endDate));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam LocalDate startDate, @RequestParam LocalDate endDate,
                                          @RequestParam(defaultValue = "all") String paymentStatus) {
        byte[] csv = salesReportService.exportCsv(ReportDateRange.start(startDate), ReportDateRange.end(endDate), paymentStatus);
        String filename = "reporte_ventas_" + startDate + "_" + endDate + ".csv";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(csv);
    }
}
