package org.uvo.uvostore.service.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GAP-05. El reparto del descuento y del IVA entre las líneas: las partes tienen que sumar exactamente el monto, o la
 * orden y el documento POS dejan de cuadrar por unos pesos.
 */
class AmountSpreadTest {

    @Test
    @DisplayName("Reparte en proporción y las partes suman exactamente el monto")
    void spreadsProportionallyAndAddsUp() {
        List<BigDecimal> shares = AmountSpread.spread(amount(1000), List.of(amount(1), amount(1), amount(1)));

        // 333,33 cada una: redondeadas dan 999, y el peso que falta va a la de mayor peso (con empate, la primera).
        assertThat(shares).containsExactly(amount(334), amount(333), amount(333));
        assertThat(sum(shares)).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("El resto va a la línea de mayor peso, no a la última")
    void theRemainderGoesToTheLargestWeight() {
        List<BigDecimal> shares = AmountSpread.spread(amount(11), List.of(amount(5), amount(5), amount(90)));

        // 0,55 + 0,55 + 9,9 → 1 + 1 + 10 = 12: sobra uno, y lo devuelve la de mayor peso. Si lo devolviera una línea
        // chica, con otros números podría quedar negativa.
        assertThat(shares).containsExactly(amount(1), amount(1), amount(9));
    }

    @Test
    @DisplayName("Un monto cero da ceros")
    void aZeroAmountGivesZeros() {
        assertThat(AmountSpread.spread(BigDecimal.ZERO, List.of(amount(9990), amount(4990))))
                .allSatisfy(share -> assertThat(share).isEqualByComparingTo("0"));
    }

    @Test
    @DisplayName("Una sola línea se lleva todo")
    void aSingleLineTakesItAll() {
        assertThat(AmountSpread.spread(amount(1740), List.of(amount(11900)))).containsExactly(amount(1740));
    }

    @Test
    @DisplayName("Una línea de peso cero no recibe nada; si todas pesan cero, el monto no se pierde")
    void zeroWeights() {
        assertThat(AmountSpread.spread(amount(100), List.of(BigDecimal.ZERO, amount(50))))
                .containsExactly(amount(0), amount(100));
        assertThat(sum(AmountSpread.spread(amount(100), List.of(BigDecimal.ZERO, BigDecimal.ZERO))))
                .isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("Sin líneas, sin partes")
    void noLines() {
        assertThat(AmountSpread.spread(amount(100), List.of())).isEmpty();
    }

    private static BigDecimal amount(int value) {
        return BigDecimal.valueOf(value);
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
