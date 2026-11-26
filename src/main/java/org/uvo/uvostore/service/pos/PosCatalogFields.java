package org.uvo.uvostore.service.pos;

import java.math.BigDecimal;

/**
 * POS-3 e IMP-08. Lo que el POS manda de uno de sus Productos (contrato §4 y §5): solo lo que es suyo. Un {@code null}
 * es "no viene" y no se escribe: {@code product.updated} trae solo lo que cambió. Que se escriba o no lo que sí viene
 * lo deciden los flags del mapeo ({@link PosCatalogWriter}).
 *
 * @param stockWeb el stock que ofrece a la web; la conciliación lo pasa nulo porque lo corrige por su cuenta
 */
public record PosCatalogFields(String name, BigDecimal price, Boolean taxExempt, Integer stockWeb) {
}
