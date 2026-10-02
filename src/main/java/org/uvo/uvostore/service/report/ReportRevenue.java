package org.uvo.uvostore.service.report;

import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.repository.OrderRefundRepository;
import org.uvo.uvostore.service.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * F20. El único sitio que decide cuánto dinero dejó una orden, con el mismo papel que {@code Money} para
 * el redondeo y {@code SettingValues} para los ajustes.
 *
 * <p>Los tres servicios de informes tenían cada uno su propio {@code map(Order::getTotal)} sobre las
 * órdenes {@code PAID}, y <b>un reembolso parcial deja la orden en PAID</b> a propósito
 * ({@code RefundService.finish}: *"una devolución parcial no deshace la compra"*). Así que el dinero
 * devuelto seguía contando como ingreso, en los tres informes, sin ningún síntoma: la cifra simplemente
 * salía más alta que la real.
 *
 * <p><b>El reembolso se imputa a la fecha de la orden, no a la del reembolso.</b> Una devolución hecha en
 * noviembre de una venta de octubre baja los ingresos de octubre. Es lo que responde a "¿cuánto dejó de
 * verdad lo que vendí ese mes?" y es coherente con que el informe se construya desde las órdenes del
 * rango. La otra lectura —imputar al movimiento de caja— es igual de legítima en contabilidad, pero
 * obligaría a traer reembolsos de órdenes ajenas al rango y permitiría una fila diaria negativa sin
 * ninguna venta.
 */
@Component
public class ReportRevenue {

    private final OrderRefundRepository refundRepository;

    public ReportRevenue(OrderRefundRepository refundRepository) {
        this.refundRepository = refundRepository;
    }

    /**
     * Bruto, devuelto y neto de un conjunto de órdenes, contando solo las pagadas.
     *
     * <p>Una sola consulta de reembolsos para todas, en vez de una por orden.
     */
    public Totals totals(List<Order> orders) {
        List<Order> paid = orders.stream().filter(o -> o.getPaymentStatus() == PaymentStatus.PAID).toList();
        if (paid.isEmpty()) {
            return new Totals(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        Map<Long, BigDecimal> refunds = refundedByOrder(paid);
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal refunded = BigDecimal.ZERO;
        for (Order order : paid) {
            gross = gross.add(order.getTotal());
            refunded = refunded.add(refunds.getOrDefault(order.getId(), BigDecimal.ZERO));
        }
        return new Totals(Money.round(gross), Money.round(refunded));
    }

    /** Lo devuelto de cada orden, cero cuando no tiene reembolsos. */
    public Map<Long, BigDecimal> refundedByOrder(List<Order> orders) {
        if (orders.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> byOrder = new HashMap<>();
        for (Object[] row : refundRepository.refundedByOrder(orders.stream().map(Order::getId).toList())) {
            byOrder.put((Long) row[0], (BigDecimal) row[1]);
        }
        return byOrder;
    }

    /**
     * Lo que de verdad dejó cada línea de una orden: su parte del subtotal menos su parte del descuento
     * del cupón y de lo devuelto.
     *
     * <p>Hacía falta porque <b>{@code OrderItem} no tiene columna de descuento</b> — el del cupón vive
     * solo en {@code Order.discountAmount}—, así que los informes por producto y por categoría sumaban
     * {@code precio × cantidad} y daban más que la venta. Un pedido con un cupón de 10.000 aparecía en el
     * informe de productos por 10.000 más de lo que el cliente pagó.
     *
     * <p><b>El resto va a la última línea</b>, igual que F06 hizo con el IVA, para que la suma de las
     * partes sea exactamente el neto de la orden: repartir proporcionalmente y redondear cada parte por
     * su cuenta deja al informe por producto sin cuadrar con el de ventas por unos pesos, que es
     * precisamente el descuadre que esta tanda viene cerrando.
     */
    public Map<Long, BigDecimal> netByItem(Order order, BigDecimal refunded) {
        List<OrderItem> items = new ArrayList<>(order.getItems());
        Map<Long, BigDecimal> net = new HashMap<>();
        if (items.isEmpty()) {
            return net;
        }

        BigDecimal itemsSubtotal = items.stream()
                .map(ReportRevenue::lineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Lo que hay que repartir entre las líneas: el descuento del cupón más lo devuelto. El envío y el
        // IVA quedan fuera a propósito — no son ingreso de ningún producto.
        BigDecimal toSpread = Money.round(order.getDiscountAmount().add(refunded));
        BigDecimal distributable = itemsSubtotal.min(toSpread);

        if (itemsSubtotal.signum() == 0 || distributable.signum() <= 0) {
            for (OrderItem item : items) {
                net.merge(item.getProduct().getId(), lineAmount(item), BigDecimal::add);
            }
            return net;
        }

        BigDecimal assigned = BigDecimal.ZERO;
        for (int i = 0; i < items.size(); i++) {
            OrderItem item = items.get(i);
            BigDecimal line = lineAmount(item);
            BigDecimal share;
            if (i == items.size() - 1) {
                // El resto, para que las partes sumen el total exacto.
                share = distributable.subtract(assigned);
            } else {
                share = Money.round(distributable.multiply(line).divide(itemsSubtotal, 6, RoundingMode.HALF_UP));
                assigned = assigned.add(share);
            }
            net.merge(item.getProduct().getId(), line.subtract(share), BigDecimal::add);
        }
        return net;
    }

    private static BigDecimal lineAmount(OrderItem item) {
        return item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
    }

    /** @param refunded siempre positivo; el neto es la resta. */
    public record Totals(BigDecimal gross, BigDecimal refunded) {

        public BigDecimal net() {
            return gross.subtract(refunded);
        }
    }
}
