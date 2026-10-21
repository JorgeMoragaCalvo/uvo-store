package org.uvo.uvostore.service.pos;

import java.util.ArrayList;
import java.util.List;

/**
 * POS-4. Lo que hizo una conciliación, sumado sobre las conexiones que recorrió. Es lo que devuelve la
 * resincronización del panel.
 *
 * @param checked          productos mapeados revisados
 * @param stockCorrected   con el stock igualado al del POS
 * @param catalogCorrected con precio, nombre o descripción igualados al del POS
 * @param pendingInFlight  con el stock distinto pero una venta web que el POS todavía no tiene: no se tocan
 * @param changedMeanwhile su stock cambió mientras se leía el POS: quedan para la próxima pasada
 * @param missingInPos     el POS no los devolvió
 * @param failedConnections conexiones cuya lectura falló
 * @param errors           los motivos de esos fallos, y los de productos que no se pudieron corregir
 */
public record PosReconciliationSummary(int checked, int stockCorrected, int catalogCorrected, int pendingInFlight,
                                       int changedMeanwhile, int missingInPos, int failedConnections,
                                       List<String> errors) {

    public static PosReconciliationSummary empty() {
        return new PosReconciliationSummary(0, 0, 0, 0, 0, 0, 0, List.of());
    }

    public PosReconciliationSummary plus(PosReconciliationSummary other) {
        List<String> allErrors = new ArrayList<>(errors);
        allErrors.addAll(other.errors);
        return new PosReconciliationSummary(checked + other.checked, stockCorrected + other.stockCorrected,
                catalogCorrected + other.catalogCorrected, pendingInFlight + other.pendingInFlight,
                changedMeanwhile + other.changedMeanwhile, missingInPos + other.missingInPos,
                failedConnections + other.failedConnections, List.copyOf(allErrors));
    }
}
