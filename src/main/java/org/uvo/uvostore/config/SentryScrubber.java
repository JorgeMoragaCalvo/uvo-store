package org.uvo.uvostore.config;

import io.sentry.Breadcrumb;
import io.sentry.SentryEvent;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryException;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IMP-03. Lo que se borra de un evento antes de que salga a Sentry, un tercero.
 *
 * <p>El backend usa el SDK core, sin integración de servlet, así que no envía URLs ni cabeceras de la petición. Lo
 * que sí sale es texto libre: el mensaje de {@code captureMessage}, el de cada excepción (de cualquier librería),
 * los {@code extra} y los breadcrumbs. Ahí puede terminar un {@code ?token=…}, un {@code password=…} o una cabecera
 * {@code Authorization: Bearer …} que alguien concatenó en un mensaje.
 *
 * <p>El criterio es el mismo que el del frontend ({@code frontend/src/lib/sentry.ts}): por el NOMBRE del dato, no
 * por una lista de sitios. Se conserva el nombre y se sustituye el valor por {@value #FILTERED}, para que el evento
 * siga diciendo qué había. Prefiere de más: {@code tokenVersion=3} también queda filtrado.
 */
public final class SentryScrubber {

    public static final String FILTERED = "[Filtered]";

    /** {@code nombre=valor} o {@code nombre: valor}, con el nombre conteniendo una palabra sensible. */
    private static final Pattern NAMED_VALUE = Pattern.compile(
            "(?i)([\\w.-]*(?:token|password|passwd|secret|signature|api[_-]?key|credential)[\\w.-]*)"
                    + "(\\s*[=:]\\s*)(\"?)([^\\s&\"',;}\\]]+)");

    private static final Pattern BEARER = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+");

    private SentryScrubber() {
    }

    /** El {@code beforeSend} del SDK. Nunca descarta el evento: solo lo limpia. */
    public static SentryEvent scrub(SentryEvent event) {
        Message message = event.getMessage();
        if (message != null) {
            message.setMessage(scrub(message.getMessage()));
            message.setFormatted(scrub(message.getFormatted()));
        }
        if (event.getExceptions() != null) {
            for (SentryException exception : event.getExceptions()) {
                exception.setValue(scrub(exception.getValue()));
            }
        }
        Map<String, Object> extras = event.getExtras();
        if (extras != null) {
            for (Map.Entry<String, Object> extra : Map.copyOf(extras).entrySet()) {
                if (extra.getValue() instanceof String text) {
                    event.setExtra(extra.getKey(), scrub(text));
                }
            }
        }
        if (event.getBreadcrumbs() != null) {
            for (Breadcrumb breadcrumb : event.getBreadcrumbs()) {
                breadcrumb.setMessage(scrub(breadcrumb.getMessage()));
            }
        }
        return event;
    }

    /** Sustituye los valores sensibles de un texto. {@code null} queda {@code null}. */
    public static String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String withoutAuth = BEARER.matcher(text).replaceAll(match -> match.group(1) + " " + FILTERED);
        Matcher named = NAMED_VALUE.matcher(withoutAuth);
        return named.replaceAll(match -> Matcher.quoteReplacement(
                match.group(1) + match.group(2) + match.group(3) + FILTERED));
    }
}
