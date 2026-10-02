package org.uvo.uvostore.service.report;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * F20. El único sitio que decide en qué zona horaria vive un informe.
 *
 * <p>Antes no había ninguno: los límites del rango se calculaban en UTC
 * ({@code ReportDateRange}, usado por los tres controladores) y la etiqueta del día también
 * ({@code SalesReportServiceImpl.dateKey}). Para una tienda chilena —UTC-3— eso significa que un informe
 * de "1 a 31 de octubre" cubría en realidad <b>del 30 de septiembre a las 21:00 al 31 de octubre a las
 * 20:59</b>: entraban tres horas de septiembre y <b>se caían las ventas del 31 después de las 21:00</b>,
 * que aparecían en noviembre. Las ventas de la tarde de fin de mes se contaban en el mes siguiente, y por
 * eso los totales mensuales no cuadraban con nada.
 *
 * <p>Las dos mitades —el rango y la agrupación— tienen que salir de aquí, juntas. Si solo se arreglara la
 * etiqueta, el primer y el último día del informe saldrían truncados; si solo el rango, las filas
 * seguirían con el día equivocado.
 *
 * <p>El límite superior es <b>exclusivo</b> a propósito: el anterior era {@code 23:59:59} con una consulta
 * inclusiva, así que una orden creada en la última fracción de segundo del día no aparecía en ningún
 * informe.
 *
 * <p><b>Es una propiedad de plataforma y no un ajuste por tienda</b> porque hoy todas las tiendas son
 * chilenas: el catálogo de divisas de F17 solo admite CLP y la SPA formatea en {@code es-CL}. El día que
 * eso cambie, esto pasa a ser un ajuste por tienda y le toca la misma pregunta de catálogo que tuvo la
 * divisa — y entonces este bean es el sitio donde se cambia.
 */
@Component
public class ReportZone {

    private final ZoneId zone;

    public ReportZone(@Value("${app.reports.timezone:America/Santiago}") String timezone) {
        try {
            this.zone = ZoneId.of(timezone);
            // DateTimeException cubre tanto el formato inválido como ZoneRulesException (zona bien
            // formada pero desconocida), que es su subclase.
        } catch (java.time.DateTimeException e) {
            // Falla el arranque en vez de caer a UTC en silencio, que es el mismo criterio que
            // SettingValues con los ajustes de dinero: un informe que agrupa por la zona equivocada no
            // avisa de nada, solo da cifras distintas.
            throw new IllegalStateException(
                    "app.reports.timezone no es una zona horaria válida: '" + timezone
                            + "'. Usa un identificador de la base de datos IANA, por ejemplo America/Santiago.", e);
        }
    }

    public ZoneId zone() {
        return zone;
    }

    /** El primer instante del día pedido, en la zona del informe. Límite inferior inclusivo. */
    public Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(zone).toInstant();
    }

    /** El primer instante del día siguiente. Límite superior <b>exclusivo</b>. */
    public Instant startOfNextDay(LocalDate date) {
        return date.plusDays(1).atStartOfDay(zone).toInstant();
    }

    /** A qué día pertenece este instante, para agrupar y etiquetar filas. */
    public String dateKey(Instant instant) {
        return instant.atZone(zone).toLocalDate().toString();
    }
}
