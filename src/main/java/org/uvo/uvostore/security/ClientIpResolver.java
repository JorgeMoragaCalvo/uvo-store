package org.uvo.uvostore.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * F18. El único sitio que decide de qué dirección viene una petición.
 *
 * <p>{@code RateLimitFilter} tomaba la primera entrada de {@code X-Forwarded-For} sin comprobar de quién
 * venía, así que quien mandaba esa cabecera elegía su propio contador: el límite no existía para nadie
 * que lo intentara. Y lo que quedaba sin límite no era poca cosa — fuerza bruta contra el login de
 * administración, un correo real por cada acierto de «olvidé mi contraseña», enumeración de números de
 * orden y la cuota de MercadoPago, que paga el comerciante.
 *
 * <p><b>Dos reglas, y las dos hacen falta:</b>
 *
 * <p>1. La cabecera solo se mira si el <b>par del socket</b> está en la lista de proxies confiables. Sin
 * lista —el valor por defecto— se ignora siempre y se usa {@code getRemoteAddr()}. Hoy no hay ningún
 * proxy delante (no hay nada en el repositorio que lo declare), así que ese defecto es además el
 * correcto.
 *
 * <p>2. La cadena se recorre <b>de derecha a izquierda</b>, no al revés. Tomar el extremo izquierdo está
 * mal incluso con un proxy confiable delante: la receta estándar de nginx es
 * {@code proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for}, que <b>añade</b> a lo que mandó el
 * cliente. Un atacante que envía {@code X-Forwarded-For: 1.2.3.4} llega como {@code 1.2.3.4, <su IP>}, y
 * la primera entrada es la suya. La del extremo derecho es la que escribió el proxy, que es la única que
 * no puede falsificar.
 *
 * <p>Y hay un efecto de segundo orden que se cierra con esto: la caché de contadores tiene un tope de
 * tamaño, así que con la clave falsificable un atacante no solo escapaba de su propio cubo, con cien mil
 * direcciones inventadas <b>desalojaba los contadores de todo el mundo</b>.
 *
 * <p><b>No usar {@code server.forward-headers-strategy=framework} para esto.</b> El
 * {@code ForwardedHeaderFilter} de Spring sí sobrescribe {@code getRemoteAddr()} desde
 * {@code X-Forwarded-For}, pero no tiene ninguna noción de proxy confiable: confía a ciegas. Activarlo
 * extendería el fallo del limitador a todo lo que lea la dirección, y además haría que
 * {@code getServerName()} saliera de {@code X-Forwarded-Host} —otra cabecera del cliente—, de donde
 * {@code MercadoPagoController} compone la URL de notificación de pagos.
 */
@Component
public class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

    private final List<IpAddressMatcher> trustedProxies;
    private final String trustedProxiesDescription;

    public ClientIpResolver(@Value("${app.rate-limit.trusted-proxies:}") String trustedProxies) {
        List<IpAddressMatcher> matchers = new ArrayList<>();
        List<String> accepted = new ArrayList<>();
        for (String entry : Arrays.stream(trustedProxies.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()) {
            try {
                matchers.add(new IpAddressMatcher(entry));
                accepted.add(entry);
            } catch (IllegalArgumentException e) {
                // Una entrada mal escrita no puede valer como "confío en todo" ni tirar el arranque:
                // se descarta avisando, y lo que quede sigue siendo la lista.
                log.error("app.rate-limit.trusted-proxies: '{}' no es una IP ni un CIDR válido, se ignora", entry);
            }
        }
        this.trustedProxies = List.copyOf(matchers);
        this.trustedProxiesDescription = accepted.isEmpty() ? "" : String.join(", ", accepted);

        // La única señal que va a tener quien despliegue. El error de operación es silencioso en los dos
        // sentidos: detrás de un proxy sin configurar esto, TODOS los clientes comparten un contador y el
        // login se corta a los cinco intentos del conjunto; y con esto mal puesto, se vuelve a confiar en
        // lo que no se debe.
        if (this.trustedProxies.isEmpty()) {
            log.info("Límite por IP: se ignora X-Forwarded-For y se usa la dirección del socket. "
                    + "Si hay un proxy inverso delante, configura app.rate-limit.trusted-proxies.");
        } else {
            log.info("Límite por IP: se confía en X-Forwarded-For procedente de [{}]", this.trustedProxiesDescription);
        }
    }

    /** La dirección desde la que de verdad llega esta petición. Nunca nula. */
    public String resolve(HttpServletRequest request) {
        String socketAddress = request.getRemoteAddr();
        if (trustedProxies.isEmpty() || !isTrusted(socketAddress)) {
            return socketAddress;
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return socketAddress;
        }

        String[] chain = forwarded.split(",");
        for (int i = chain.length - 1; i >= 0; i--) {
            String candidate = chain[i].trim();
            if (candidate.isEmpty()) {
                continue;
            }
            // La validez va PRIMERO, y no es cosmética: IpAddressMatcher.matches(String) lanza
            // IllegalArgumentException ante algo que no sea una dirección, así que comprobar la
            // confianza antes convertía cualquier cabecera con basura en un 500. Lo descubrió el test.
            //
            // Y si no es una dirección no se puede identificar a nadie: se prefiere agrupar a todos bajo
            // el proxy —limitar de más— antes que aceptar como clave un texto que escribe el atacante.
            if (!isAddress(candidate)) {
                log.debug("X-Forwarded-For con una entrada que no es una dirección; se usa el socket");
                return socketAddress;
            }
            if (isTrusted(candidate)) {
                continue;
            }
            return candidate;
        }
        // Toda la cadena es de proxies confiables: no hay cliente que distinguir.
        return socketAddress;
    }

    private boolean isTrusted(String address) {
        for (IpAddressMatcher matcher : trustedProxies) {
            try {
                if (matcher.matches(address)) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // Por si llega aquí algo que no es una dirección: no confiar nunca es la respuesta
                // segura, y desde luego no propagar un 500 desde el limitador.
                return false;
            }
        }
        return false;
    }

    // Reutiliza el parseo de la propia librería en vez de escribir uno a mano: IpAddressMatcher rechaza
    // cualquier cosa que no sea IPv4, IPv6 o un CIDR, y no resuelve nombres (InetAddress.getByName sí lo
    // haría, y eso sería una consulta DNS por petición a lo que diga un atacante). El CIDR se descarta
    // aquí porque una entrada de la cadena es una dirección, no un rango. Cuesta un objeto pequeño por
    // salto y solo en el camino con proxy confiable, que es tráfico legítimo y de cadena corta.
    private static boolean isAddress(String candidate) {
        if (candidate.indexOf('/') >= 0) {
            return false;
        }
        try {
            new IpAddressMatcher(candidate);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
