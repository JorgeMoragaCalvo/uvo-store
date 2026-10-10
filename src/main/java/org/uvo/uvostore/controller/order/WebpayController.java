package org.uvo.uvostore.controller.order;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.payment.WebpayCommitResult;
import org.uvo.uvostore.service.payment.WebpayCreateResult;
import org.uvo.uvostore.service.payment.WebpayService;
import org.uvo.uvostore.service.url.StorePublicUrlResolver;

import java.net.URI;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Webpay (público)", description = "Creación y confirmación de transacciones Webpay Plus")
@RestController
@RequestMapping("/api/v1/webpay")
public class WebpayController {

    private static final Logger log = LoggerFactory.getLogger(WebpayController.class);

    private final WebpayService webpayService;
    private final StorePublicUrlResolver publicUrls;

    public WebpayController(WebpayService webpayService, StorePublicUrlResolver publicUrls) {
        this.webpayService = webpayService;
        this.publicUrls = publicUrls;
    }

    // PROD-04. El `returnUrl` ya no viene en el cuerpo: lo resuelve el servicio desde la tienda de la
    // orden. Aceptarlo del cliente era dejar que quien llama elija dónde acaba el pagador después de
    // pagar, y el servidor conoce ese dato mejor que el navegador.
    @PostMapping("/create")
    public WebpayCreateResult create(@Valid @RequestBody WebpayCreateRequest request) {
        return webpayService.createTransaction(request.orderId());
    }

    // Transbank POSTs the customer's browser directly to this URL after they finish paying —
    // form-encoded, not JSON, and there's no frontend route that could receive a raw redirect
    // POST like this. We commit the transaction here and 302 the browser to the SPA's result page.
    //
    // PROD-04: a la SPA de SU tienda. Antes salía de la URL global, así que el comprador de cualquier
    // tienda terminaba en el storefront de una sola — y es el único de los tres caminos de retorno que el
    // cliente no podía sobreescribir, así que no había forma de que fuera correcto.
    @PostMapping("/return")
    public ResponseEntity<Void> handleReturn(
            @RequestParam(name = "token_ws", required = false) String tokenWs,
            @RequestParam(name = "TBK_TOKEN", required = false) String tbkToken) {
        Store store = TenantContext.requireCurrent();
        if (tokenWs == null) {
            // User aborted on Transbank's own page (TBK_TOKEN case) — nothing to commit.
            return redirectTo(publicUrls.storefrontUrl(store, "/checkout?canceled=1"));
        }

        try {
            WebpayCommitResult result = webpayService.commitTransaction(tokenWs);
            return redirectTo(publicUrls.storefrontUrl(store, "/order-success?order=" + result.orderNumber()));
        } catch (Exception e) {
            log.warn("Error confirmando transacción Webpay token={}: {}", tokenWs, e.getMessage());
            return redirectTo(publicUrls.storefrontUrl(store, "/checkout?error=webpay"));
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
}
