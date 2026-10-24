package org.uvo.uvostore.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

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
 *
 * <p><b>POS-6.</b> La lista se compara con {@link IpRanges} y no con {@code IpAddressMatcher} de Spring, que en la
 * 7.1.0 no distingue IPv4 de IPv6 (un proxy confiable {@code 10.0.0.0/8} aceptaba la IPv6 {@code a00::1}). Y
 * {@link #resolveStrict} es la variante para decidir permisos, que no puede caer en la dirección del socket.
 */
@Component
public class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

    private final IpRanges trustedProxies;

    public ClientIpResolver(@Value("${app.rate-limit.trusted-proxies:}") String trustedProxies) {
        // Una entrada mal escrita no puede valer como "confío en todo" ni tirar el arranque: se descarta
        // avisando, y lo que quede sigue siendo la lista.
        this.trustedProxies = IpRanges.parseLenient(trustedProxies, entry -> log.error(
                "app.rate-limit.trusted-proxies: '{}' no es una IP ni un CIDR válido, se ignora", entry));

        // La única señal que va a tener quien despliegue. El error de operación es silencioso en los dos
        // sentidos: detrás de un proxy sin configurar esto, TODOS los clientes comparten un contador y el
        // login se corta a los cinco intentos del conjunto; y con esto mal puesto, se vuelve a confiar en
        // lo que no se debe.
        if (this.trustedProxies.isEmpty()) {
            log.info("Límite por IP: se ignora X-Forwarded-For y se usa la dirección del socket. "
                    + "Si hay un proxy inverso delante, configura app.rate-limit.trusted-proxies.");
        } else {
            log.info("Límite por IP: se confía en X-Forwarded-For procedente de [{}]", this.trustedProxies.describe());
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
            // La validez va PRIMERO. Con IpAddressMatcher de Spring no era cosmética: lanzaba ante algo que no
            // fuera una dirección, y comprobar la confianza antes convertía cualquier cabecera con basura en un
            // 500 (lo descubrió el test). IpRanges ya no lanza, pero el orden sigue decidiendo qué se devuelve:
            // si no es una dirección no se puede identificar a nadie, y se prefiere agrupar a todos bajo el
            // proxy —limitar de más— antes que aceptar como clave un texto que escribe el atacante.
            if (!IpRanges.isAddress(candidate)) {
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

    /**
     * POS-6. La dirección del cliente para decidir un <b>permiso</b> (la lista de redes de la API de plataforma), o
     * vacío si no se puede saber con certeza.
     *
     * <p>{@link #resolve} cae en la dirección del socket cuando duda, y para el limitador es lo correcto: agrupa de
     * más. Para un permiso es justo lo contrario, porque detrás de un proxy esa dirección es la del proxy, que es
     * privada: la duda dejaría pasar a todo Internet. Por eso aquí la duda es un no:
     * <ul>
     *   <li>llega una cabecera de reenvío ({@code X-Forwarded-For}, {@code Forwarded} o {@code X-Real-IP}) desde un
     *       par que no es proxy confiable: hay un proxy delante que nadie declaró, o alguien la escribe a mano;</li>
     *   <li>un proxy confiable no dice de quién viene la petición;</li>
     *   <li>un salto de la cadena no es una dirección.</li>
     * </ul>
     * Lo que <b>no</b> puede detectar es un proxy que no añada ninguna cabecera: entonces todo llega con su
     * dirección privada. Que el proxy ponga {@code X-Forwarded-For} es parte de su configuración (CLAUDE.md, POS-6).
     */
    public Optional<String> resolveStrict(HttpServletRequest request) {
        String socketAddress = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (!isTrusted(socketAddress)) {
            if (hasText(forwarded) || hasText(request.getHeader("Forwarded")) || hasText(request.getHeader("X-Real-IP"))) {
                return Optional.empty();
            }
            return IpRanges.isAddress(socketAddress) ? Optional.of(socketAddress) : Optional.empty();
        }
        if (!hasText(forwarded)) {
            return Optional.empty();
        }
        String origin = null;
        String[] chain = forwarded.split(",");
        for (int i = chain.length - 1; i >= 0; i--) {
            String candidate = chain[i].trim();
            if (!IpRanges.isAddress(candidate)) {
                return Optional.empty();
            }
            if (!isTrusted(candidate)) {
                return Optional.of(candidate);
            }
            origin = candidate;
        }
        // Toda la cadena es de proxies confiables: la petición nació en uno de ellos, el del extremo izquierdo.
        return Optional.ofNullable(origin);
    }

    private boolean isTrusted(String address) {
        return trustedProxies.matches(address);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
