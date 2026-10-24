package org.uvo.uvostore.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * POS-6. Una lista de rangos de IP (direcciones sueltas o CIDR) y la pregunta de si una dirección cae en alguno.
 *
 * <p><b>Por qué no {@code IpAddressMatcher} de Spring Security.</b> En la 7.1.0 que usa el proyecto compara bytes
 * sin mirar si la dirección y el rango son de la misma familia. Comprobado con un programa contra el jar:
 * <ul>
 *   <li>{@code 10.0.0.0/8} acepta la IPv6 {@code a00::1}: compara el primer byte y ya;</li>
 *   <li>{@code ::1/128} contra {@code 0.0.0.0} lanza {@code ArrayIndexOutOfBoundsException}, que desde un filtro
 *       es un 500;</li>
 *   <li>{@code "10"} se lee como {@code 0.0.0.10}.</li>
 * </ul>
 * Para la lista de redes de la API de plataforma eso es inaceptable, y para los proxies confiables de F18 es el
 * mismo defecto latente: por eso los dos usan esta clase.
 *
 * <p><b>Qué acepta:</b> IPv4 en cuatro octetos decimales, o una IPv6 literal (lleva {@code :}), con {@code /n}
 * opcional. <b>Nunca resuelve nombres</b>: un texto sin {@code :} que no sea un IPv4 de cuatro octetos se rechaza
 * antes de llegar a {@link InetAddress}, que con un nombre haría una consulta DNS. Una IPv6 con el IPv4 incrustado
 * ({@code ::ffff:10.0.0.1}) cuenta como ese IPv4, que es lo que {@code InetAddress} ya hace y lo que significa.
 */
public final class IpRanges {

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private record Range(String text, byte[] network, int prefix) {

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                // La comprobación que le falta a Spring: una IPv4 nunca está en un rango IPv6, ni al revés.
                return false;
            }
            int fullBytes = prefix / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefix % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    private final List<Range> ranges;

    private IpRanges(List<Range> ranges) {
        this.ranges = List.copyOf(ranges);
    }

    /**
     * La lista, o una excepción que nombra la primera entrada que no se entiende. Para lo que debe impedir el
     * arranque si está mal escrito.
     */
    public static IpRanges parseStrict(String commaSeparated) {
        return parse(commaSeparated, entry -> {
            throw new IllegalArgumentException("'" + entry + "' no es una IP ni un CIDR válido");
        });
    }

    /** La lista sin las entradas que no se entienden, avisando de cada una. */
    public static IpRanges parseLenient(String commaSeparated, Consumer<String> onInvalid) {
        return parse(commaSeparated, onInvalid);
    }

    private static IpRanges parse(String commaSeparated, Consumer<String> onInvalid) {
        List<Range> parsed = new ArrayList<>();
        if (commaSeparated != null) {
            for (String entry : Arrays.stream(commaSeparated.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()) {
                Range range = parseRange(entry);
                if (range == null) {
                    onInvalid.accept(entry);
                } else {
                    parsed.add(range);
                }
            }
        }
        return new IpRanges(parsed);
    }

    private static Range parseRange(String entry) {
        String address = entry;
        Integer prefix = null;
        int slash = entry.indexOf('/');
        if (slash >= 0) {
            address = entry.substring(0, slash);
            String bits = entry.substring(slash + 1);
            if (!bits.matches("\\d{1,3}")) {
                return null;
            }
            prefix = Integer.parseInt(bits);
        }
        byte[] network = parseAddress(address);
        if (network == null) {
            return null;
        }
        int maxBits = network.length * 8;
        int effective = prefix == null ? maxBits : prefix;
        if (effective > maxBits) {
            return null;
        }
        return new Range(entry, network, effective);
    }

    /** Si es una dirección IPv4 o IPv6 literal, sin rango y sin resolver nombres. */
    public static boolean isAddress(String candidate) {
        return candidate != null && candidate.indexOf('/') < 0 && parseAddress(candidate) != null;
    }

    /** Los bytes de la dirección (4 o 16), o null si no es una dirección literal. */
    private static byte[] parseAddress(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher ipv4 = IPV4.matcher(text);
        if (ipv4.matches()) {
            byte[] bytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                int octet = Integer.parseInt(ipv4.group(i + 1));
                if (octet > 255) {
                    return null;
                }
                bytes[i] = (byte) octet;
            }
            return bytes;
        }
        // Una dirección de enlace local puede llegar del socket con su zona (fe80::1%eth0): la zona no es parte de
        // la dirección para decidir si está en un rango.
        int zone = text.indexOf('%');
        String literal = zone >= 0 ? text.substring(0, zone) : text;
        if (literal.indexOf(':') < 0 || !IPV6_CHARS.matcher(literal).matches()) {
            return null;
        }
        try {
            // Con ':' InetAddress lo trata como literal IPv6 y no consulta DNS: si no lo es, lanza. Una IPv6 con el
            // IPv4 incrustado sale como Inet4Address, de 4 bytes.
            return InetAddress.getByName(literal).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Si la dirección cae en algún rango. Falso para lo que no es una dirección, nunca una excepción. */
    public boolean matches(String address) {
        byte[] bytes = parseAddress(address);
        if (bytes == null) {
            return false;
        }
        for (Range range : ranges) {
            if (range.contains(bytes)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return ranges.isEmpty();
    }

    /** Si algún rango lo abarca todo ({@code /0}). */
    public boolean coversEverything() {
        return ranges.stream().anyMatch(range -> range.prefix() == 0);
    }

    /** Las entradas aceptadas, separadas por coma, para el log. */
    public String describe() {
        return String.join(", ", ranges.stream().map(Range::text).toList());
    }
}
