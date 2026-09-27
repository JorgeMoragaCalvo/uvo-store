package org.uvo.uvostore.service.catalog;

import org.uvo.uvostore.entity.catalog.Product;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * F11. El precio que de verdad se cobra por un producto, en un solo sitio.
 *
 * <p>El admin guardaba {@code isOnSale}, {@code salePrice} y su ventana de fechas
 * ({@code ProductServiceImpl.applyCommonFields}) y <b>nadie los leía nunca</b>: el catálogo, la
 * validación del carrito, el cálculo y el snapshot del {@code OrderItem} tomaban todos
 * {@code product.getPrice()}. Una oferta era un campo de solo escritura, así que el día que algo la
 * pusiera —la API de admin ya la acepta— el producto aparecería en "Ofertas" cobrado a precio completo.
 *
 * <p><b>Las variaciones no pasan por aquí, y es correcto.</b> Ellas usan el convenio contrario y mejor:
 * {@code price} es lo que se paga y {@code compareAtPrice} el precio tachado, así que su oferta no puede
 * dejar de aplicarse en silencio. No tienen {@code salePrice}. Al exponer aquí el precio vigente en
 * {@code price} y el original en {@code compareAtPrice}, las dos mitades del catálogo acaban hablando
 * igual.
 *
 * <p><b>Una oferta mal configurada no rompe nada: vale el precio normal.</b> Activada sin precio, con
 * precio cero o con un precio <i>mayor</i> que el habitual — en los tres casos se cobra {@code price}. El
 * catálogo no es sitio para lanzar excepciones, y un fallo de configuración no debe poder subir el precio
 * ni tumbar la ficha.
 */
public final class EffectivePrice {

    private EffectivePrice() {
    }

    /** El precio a cobrar hoy: la oferta si está vigente y es creíble, y si no el precio normal. */
    public static BigDecimal of(Product product) {
        return of(product, Instant.now());
    }

    // Instant explícito para poder probar las dos orillas de la ventana sin depender del reloj.
    static BigDecimal of(Product product, Instant now) {
        BigDecimal regular = product.getPrice();
        if (!product.isOnSale()) {
            return regular;
        }
        BigDecimal sale = product.getSalePrice();
        if (sale == null || sale.signum() <= 0 || regular == null || sale.compareTo(regular) >= 0) {
            return regular;
        }
        if (product.getSaleStartsAt() != null && now.isBefore(product.getSaleStartsAt())) {
            return regular;
        }
        if (product.getSaleEndsAt() != null && now.isAfter(product.getSaleEndsAt())) {
            return regular;
        }
        return sale;
    }

    /**
     * El precio tachado: el habitual cuando hay oferta vigente, y {@code null} cuando no hay nada que
     * tachar. Mismo significado que {@code ProductVariation.compareAtPrice}.
     */
    public static BigDecimal compareAtPrice(Product product) {
        BigDecimal effective = of(product);
        return effective != null && effective.compareTo(product.getPrice()) < 0 ? product.getPrice() : null;
    }
}
