package org.uvo.uvostore.payment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.uvo.uvostore.controller.order.WebpayController;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.payment.WebpayCommitResult;
import org.uvo.uvostore.service.payment.WebpayService;
import org.uvo.uvostore.service.url.StorePublicUrlResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GAP-08. Adónde vuelve el comprador después de confirmar en Webpay, según cómo quedó el pago.
 *
 * <p>Un rechazo de Transbank no lanza: {@code commitTransaction} marca el pago FAILED y vuelve con normalidad.
 * El controlador redirigía igual a la página de éxito, que decía «¡Gracias por tu compra!» y vaciaba el carrito
 * de un pedido sin pagar. Lo que hace {@code commitTransaction} con cada respuesta ya lo cubren
 * {@code PaymentInterruptionTest} y el E2E de pasarelas; aquí solo importa la redirección.
 */
class WebpayReturnOutcomeTest {

    private final WebpayService webpayService = mock(WebpayService.class);
    private final StorePublicUrlResolver publicUrls = mock(StorePublicUrlResolver.class);
    private final WebpayController controller = new WebpayController(webpayService, publicUrls);

    @BeforeEach
    void enterStore() {
        TenantContext.set(new Store());
        when(publicUrls.storefrontUrl(any(), anyString())).thenAnswer(call -> "http://tienda.test" + call.getArgument(1));
    }

    @AfterEach
    void leaveStore() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Un pago autorizado vuelve a la página de éxito")
    void anAuthorizedPaymentGoesToTheSuccessPage() {
        when(webpayService.commitTransaction("tok")).thenReturn(new WebpayCommitResult(1L, "ORD-1", "AUTHORIZED", "PAID"));

        assertThat(location(controller.handleReturn("tok", null))).isEqualTo("http://tienda.test/order-success?order=ORD-1");
    }

    @Test
    @DisplayName("Un pago rechazado vuelve al checkout con el aviso, no a la página de éxito")
    void aRejectedPaymentGoesBackToCheckout() {
        when(webpayService.commitTransaction("tok")).thenReturn(new WebpayCommitResult(1L, "ORD-1", "FAILED", "FAILED"));

        assertThat(location(controller.handleReturn("tok", null))).isEqualTo("http://tienda.test/checkout?error=webpay");
    }

    @Test
    @DisplayName("Un cobro sobre una orden ya cerrada tampoco da las gracias")
    void aPaymentOnAClosedOrderDoesNotThankTheBuyer() {
        // GAP-04: markPaid no reactiva una orden cancelada; deja la nota y la alerta, y el pago sigue FAILED.
        when(webpayService.commitTransaction("tok")).thenReturn(new WebpayCommitResult(1L, "ORD-1", "AUTHORIZED", "FAILED"));

        assertThat(location(controller.handleReturn("tok", null))).isEqualTo("http://tienda.test/checkout?error=webpay");
    }

    private static String location(ResponseEntity<Void> response) {
        return String.valueOf(response.getHeaders().getLocation());
    }
}
