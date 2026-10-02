package org.uvo.uvostore.controller.admin.report;

import org.springframework.stereotype.Component;
import org.uvo.uvostore.service.report.ReportZone;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Convierte el {@code startDate}/{@code endDate} que pide el panel en el rango de instantes que consultan
 * los informes. Lo comparten los tres controladores.
 *
 * <p>F20. Dos cambios, y los dos corregían cifras:
 *
 * <p><b>La zona.</b> Era {@code ZoneOffset.UTC}, así que para una tienda chilena un informe de "1 a 31 de
 * octubre" iba en realidad del 30 de septiembre a las 21:00 al 31 de octubre a las 20:59: las ventas de la
 * tarde del último día del mes se contaban en el mes siguiente. Ahora sale de {@link ReportZone}, el mismo
 * sitio del que sale la etiqueta del día de cada fila, para que el rango y la agrupación no puedan
 * discrepar.
 *
 * <p><b>El tope exclusivo.</b> Era {@code 23:59:59} contra una consulta inclusiva, y Postgres guarda
 * microsegundos: una orden creada a las 23:59:59,4 no aparecía en el informe de ese día ni en ningún otro.
 *
 * <p>Dejó de ser una clase de utilidad estática porque ahora depende de configuración.
 */
@Component
public class ReportDateRange {

    private final ReportZone reportZone;

    public ReportDateRange(ReportZone reportZone) {
        this.reportZone = reportZone;
    }

    /** Límite inferior, inclusivo. */
    public Instant start(LocalDate date) {
        return reportZone.startOfDay(date);
    }

    /** Límite superior, <b>exclusivo</b>: el primer instante del día siguiente. */
    public Instant endExclusive(LocalDate date) {
        return reportZone.startOfNextDay(date);
    }
}
