package org.uvo.uvostore.controller.order;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.payment.WebpayCommitResult;
import org.uvo.uvostore.service.payment.WebpayCreateResult;
import org.uvo.uvostore.service.payment.WebpayService;

import java.net.URI;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Webpay (público)", description = "Creación y confirmación de transacciones Webpay Plus")
@RestController
@RequestMapping("/api/v1/webpay")
public class WebpayController {

    private static final Logger log = LoggerFactory.getLogger(WebpayController.class);

    private final WebpayService webpayService;
    private final String frontendUrl;

    public WebpayController(WebpayService webpayService, @Value("${app.frontend-url}") String frontendUrl) {
        this.webpayService = webpayService;
        this.frontendUrl = frontendUrl;
    }

    @PostMapping("/create")
    public WebpayCreateResult create(@Valid @RequestBody WebpayCreateRequest request, HttpServletRequest httpRequest) {
        String returnUrl = request.returnUrl() != null ? request.returnUrl() : defaultReturnUrl(httpRequest);
        return webpayService.createTransaction(request.orderId(), returnUrl);
    }

    // Transbank POSTs the customer's browser directly to this URL after they finish paying —
    // form-encoded, not JSON, and there's no frontend route that could receive a raw redirect
    // POST like this. We commit the transaction here and 302 the browser to the SPA's result page.
    @PostMapping("/return")
    public ResponseEntity<Void> handleReturn(
            @RequestParam(name = "token_ws", required = false) String tokenWs,
            @RequestParam(name = "TBK_TOKEN", required = false) String tbkToken) {
        if (tokenWs == null) {
            // User aborted on Transbank's own page (TBK_TOKEN case) — nothing to commit.
            return redirectTo(frontendUrl + "/checkout?canceled=1");
        }

        try {
            WebpayCommitResult result = webpayService.commitTransaction(tokenWs);
            return redirectTo(frontendUrl + "/order-success?order=" + result.orderNumber());
        } catch (Exception e) {
            log.warn("Error confirmando transacción Webpay token={}: {}", tokenWs, e.getMessage());
            return redirectTo(frontendUrl + "/checkout?error=webpay");
        }
    }

    private ResponseEntity<Void> redirectTo(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).headers(headersWithLocation(location)).build();
    }

    private HttpHeaders headersWithLocation(String location) {
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(location));
        return headers;
    }

    private String defaultReturnUrl(HttpServletRequest request) {
        return request.getScheme() + "://" + request.getServerName()
                + (request.getServerPort() == 80 || request.getServerPort() == 443 ? "" : ":" + request.getServerPort())
                + "/api/v1/webpay/return";
    }
}
