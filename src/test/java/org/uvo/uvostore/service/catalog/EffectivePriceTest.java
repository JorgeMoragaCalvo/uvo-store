// Mismo paquete que EffectivePrice: la sobrecarga que recibe el Instant es visible en el paquete a
// propósito, para poder probar las dos orillas de la ventana sin depender del reloj.
package org.uvo.uvostore.service.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.catalog.Product;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F11. La regla del precio vigente.
 *
 * <p>El admin guardaba la oferta y nadie la leía: `salePrice` era un campo de solo escritura, así que un
 * producto podía aparecer en "Ofertas" y cobrarse a precio completo. Esta es la única función que decide
 * qué se cobra, y de ella tiran el catálogo, la validación del carrito, el cálculo y el snapshot del
 * `OrderItem`.
 *
 * <p>Lo que más importa de aquí abajo son los casos raros: una oferta mal configurada **no sube el
 * precio ni rompe la ficha**, se ignora.
 */
class EffectivePriceTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Test
    @DisplayName("Una oferta vigente es lo que se cobra")
    void aLiveSaleApplies() {
        assertThat(EffectivePrice.of(onSale("10000", "7990", null, null), NOW))
                .isEqualByComparingTo("7990");
    }

    @Test
    @DisplayName("Sin la bandera de oferta no se aplica, aunque haya un precio de oferta guardado")
    void theFlagIsRequired() {
        // Es el caso de hoy en todo el catálogo: el formulario del panel manda isOnSale=false fijo.
        Product product = onSale("10000", "7990", null, null);
        product.setOnSale(false);

        assertThat(EffectivePrice.of(product, NOW)).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("Una oferta sin precio, o de cero, se ignora")
    void anEmptyOrZeroSalePriceIsIgnored() {
        assertThat(EffectivePrice.of(onSale("10000", null, null, null), NOW)).isEqualByComparingTo("10000");
        assertThat(EffectivePrice.of(onSale("10000", "0", null, null), NOW)).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("Una oferta MÁS CARA que el precio normal no sube el precio")
    void aSalePriceAboveTheRegularOneNeverRaisesIt() {
        // El caso que de verdad hay que blindar: un error de configuración no puede cobrarle al cliente
        // más de lo que vale el producto.
        assertThat(EffectivePrice.of(onSale("10000", "12000", null, null), NOW)).isEqualByComparingTo("10000");
        // Igual de barata que el precio normal tampoco es una oferta.
        assertThat(EffectivePrice.of(onSale("10000", "10000", null, null), NOW)).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("Fuera de la ventana de fechas no se aplica")
    void outsideTheWindowItDoesNotApply() {
        Instant future = NOW.plus(10, ChronoUnit.DAYS);
        Instant past = NOW.minus(10, ChronoUnit.DAYS);

        assertThat(EffectivePrice.of(onSale("10000", "7990", future, null), NOW))
                .as("todavía no empieza").isEqualByComparingTo("10000");
        assertThat(EffectivePrice.of(onSale("10000", "7990", null, past), NOW))
                .as("ya terminó").isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("Fechas nulas son una ventana abierta")
    void nullDatesMeanOpenEnded() {
        Instant past = NOW.minus(10, ChronoUnit.DAYS);
        Instant future = NOW.plus(10, ChronoUnit.DAYS);

        assertThat(EffectivePrice.of(onSale("10000", "7990", past, future), NOW)).isEqualByComparingTo("7990");
        assertThat(EffectivePrice.of(onSale("10000", "7990", past, null), NOW)).isEqualByComparingTo("7990");
        assertThat(EffectivePrice.of(onSale("10000", "7990", null, future), NOW)).isEqualByComparingTo("7990");
    }

    @Test
    @DisplayName("El precio tachado solo existe cuando hay algo que tachar")
    void theCompareAtPriceOnlyExistsWithALiveSale() {
        assertThat(EffectivePrice.compareAtPrice(onSale("10000", "7990", null, null)))
                .isEqualByComparingTo("10000");

        Product noSale = onSale("10000", "7990", null, null);
        noSale.setOnSale(false);
        assertThat(EffectivePrice.compareAtPrice(noSale))
                .as("sin oferta no se tacha nada: el frontend no debe pintar un precio barrado")
                .isNull();
    }

    private Product onSale(String price, String salePrice, Instant startsAt, Instant endsAt) {
        Product product = new Product();
        product.setPrice(new BigDecimal(price));
        product.setOnSale(true);
        product.setSalePrice(salePrice == null ? null : new BigDecimal(salePrice));
        product.setSaleStartsAt(startsAt);
        product.setSaleEndsAt(endsAt);
        return product;
    }
}
