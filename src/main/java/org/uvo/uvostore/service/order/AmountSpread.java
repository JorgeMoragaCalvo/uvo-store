package org.uvo.uvostore.service.order;

import org.uvo.uvostore.service.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * GAP-05. Reparte un monto de la orden —el descuento del cupón o el IVA— entre sus líneas, en proporción a un peso
 * por línea.
 *
 * <p>Cada parte se redondea a pesos y <b>el resto va a la línea de mayor peso</b>, para que las partes sumen
 * exactamente el monto: es el mismo cuidado que F06 tuvo con el IVA por diferencia y que
 * {@code ReportRevenue.netByItem} tiene con el descuento. A la de mayor peso y no a la última, porque la última
 * puede ser una línea de pocos pesos y el resto la dejaría negativa.
 */
final class AmountSpread {

    private AmountSpread() {
    }

    /**
     * @param amount  un monto entero, ya redondeado
     * @param weights uno por línea, en orden; ninguno negativo
     * @return una parte por peso, en el mismo orden, que suman exactamente {@code amount}. Si los pesos suman cero,
     *         todo va a la primera línea, para no perder el monto.
     */
    static List<BigDecimal> spread(BigDecimal amount, List<BigDecimal> weights) {
        List<BigDecimal> shares = new ArrayList<>(weights.size());
        if (weights.isEmpty()) {
            return shares;
        }
        BigDecimal totalWeight = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        int largest = 0;
        for (int i = 0; i < weights.size(); i++) {
            if (weights.get(i).compareTo(weights.get(largest)) > 0) {
                largest = i;
            }
        }

        BigDecimal assigned = BigDecimal.ZERO;
        for (BigDecimal weight : weights) {
            BigDecimal share = totalWeight.signum() == 0
                    ? BigDecimal.ZERO
                    : Money.round(amount.multiply(weight).divide(totalWeight, 6, RoundingMode.HALF_UP));
            shares.add(share);
            assigned = assigned.add(share);
        }
        shares.set(largest, shares.get(largest).add(amount.subtract(assigned)));
        return shares;
    }
}
