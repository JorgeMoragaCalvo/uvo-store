package org.uvo.uvostore.controller.admin.report;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.report.CategoryRevenueDto;
import org.uvo.uvostore.service.report.ProductReportRowDto;
import org.uvo.uvostore.service.report.ProductsReportService;
import org.uvo.uvostore.service.report.ProductsSummaryDto;

import java.time.LocalDate;
import java.util.List;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Reportes de productos (admin)", description = "Ranking y desgloses de productos vendidos, JWT bearer con rol ADMIN")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/reports/products")
public class AdminProductsReportController {

    private final ProductsReportService productsReportService;

    public AdminProductsReportController(ProductsReportService productsReportService) {
        this.productsReportService = productsReportService;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('reports.view')")
    public ProductsSummaryDto summary(@RequestParam LocalDate startDate, @RequestParam LocalDate endDate,
                                       @RequestParam(required = false) Long categoryId, @RequestParam(required = false) String search) {
        return productsReportService.getSummary(ReportDateRange.start(startDate), ReportDateRange.end(endDate), categoryId, search);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('reports.view')")
    public Page<ProductReportRowDto> data(
            @RequestParam LocalDate startDate, @RequestParam LocalDate endDate,
            @RequestParam(required = false) Long categoryId, @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "revenue") String sortBy, @RequestParam(defaultValue = "desc") String sortDirection,
            @RequestParam(defaultValue = "1") int page
    ) {
        return productsReportService.getProductsData(
                ReportDateRange.start(startDate), ReportDateRange.end(endDate), categoryId, search, sortBy, sortDirection,
                PageRequest.of(Math.max(page - 1, 0), 20));
    }

    @GetMapping("/top-by-revenue")
    @PreAuthorize("hasAuthority('reports.view')")
    public List<ProductReportRowDto> topByRevenue(@RequestParam LocalDate startDate, @RequestParam LocalDate endDate) {
        return productsReportService.getTopByRevenue(ReportDateRange.start(startDate), ReportDateRange.end(endDate));
    }

    @GetMapping("/top-by-quantity")
    @PreAuthorize("hasAuthority('reports.view')")
    public List<ProductReportRowDto> topByQuantity(@RequestParam LocalDate startDate, @RequestParam LocalDate endDate) {
        return productsReportService.getTopByQuantity(ReportDateRange.start(startDate), ReportDateRange.end(endDate));
    }

    @GetMapping("/by-category")
    @PreAuthorize("hasAuthority('reports.view')")
    public List<CategoryRevenueDto> byCategory(@RequestParam LocalDate startDate, @RequestParam LocalDate endDate) {
        return productsReportService.getSalesByCategory(ReportDateRange.start(startDate), ReportDateRange.end(endDate));
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('reports.view')")
    public ResponseEntity<byte[]> export(@RequestParam LocalDate startDate, @RequestParam LocalDate endDate,
                                          @RequestParam(required = false) Long categoryId, @RequestParam(required = false) String search) {
        byte[] csv = productsReportService.exportCsv(ReportDateRange.start(startDate), ReportDateRange.end(endDate), categoryId, search);
        String filename = "reporte_productos_" + startDate + "_" + endDate + ".csv";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(csv);
    }
}
