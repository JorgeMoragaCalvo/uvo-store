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

    // Lo justo para encontrar la orden (`payment_id LIKE 'abcd1234%'`) sin que la línea de log sirva para
    // reutilizar el token: uno de Webpay tiene 64 caracteres, así que 8 dejan 56 sin revelar.
    private static final int TOKEN_PREFIX_LENGTH = 8;

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
            // PROD-17a. El token_ws completo NO se registra. Es la credencial de esa transacción frente a
            // Transbank y este endpoint es público, así que escribirlo lo deja en cada agregador y copia
            // de logs durante todo el tiempo que se conserven. Y como es entrada sin autenticar, tampoco
            // se imprime tal cual aunque sea falso: ver tokenPrefix().
            //
            // El mensaje de la excepción sí se conserva: el SDK de Transbank (6.2.1) no incluye el token
            // en los suyos —las validaciones dicen "'token' can't be null…" y los errores HTTP traen el
            // `error_message` de la respuesta— y se registra el mensaje, no la traza.
            log.warn("Error confirmando transacción Webpay store_id={} token_prefix={} error={}",
                    store.getId(), tokenPrefix(tokenWs), e.getMessage());
            return redirectTo(publicUrls.storefrontUrl(store, "/checkout?error=webpay"));
        }
    }

    // Entrada pública sin autenticar: cualquiera puede mandar `token_ws` con saltos de línea para fabricar
    // entradas de log falsas, o con megabytes para inflarlo. Por eso se recorta ANTES de sanear, y todo lo
    // que no sea alfanumérico se sustituye por '?' (un token real es hexadecimal).
    private static String tokenPrefix(String token) {
        String head = token.length() > TOKEN_PREFIX_LENGTH ? token.substring(0, TOKEN_PREFIX_LENGTH) : token;
        return head.replaceAll("[^A-Za-z0-9]", "?") + "...";
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
