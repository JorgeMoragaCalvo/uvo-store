package org.uvo.uvostore.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F18. De qué dirección se considera que viene una petición.
 *
 * <p>Las direcciones son de los rangos de documentación (RFC 5737): {@code 203.0.113.x} hace de proxy y
 * {@code 198.51.100.x} de cliente real, para que ninguna pueda confundirse con algo enrutable.
 */
class ClientIpResolverTest {

    @Test
    @DisplayName("Sin proxies confiables se ignora X-Forwarded-For")
    void withoutTrustedProxiesTheHeaderIsIgnored() {
        ClientIpResolver resolver = new ClientIpResolver("");

        // Este es el fallo entero: mandar la cabecera bastaba para elegir contador.
        assertThat(resolver.resolve(request("198.51.100.9", "1.2.3.4"))).isEqualTo("198.51.100.9");
        // Y cambiándola en cada intento, para tener uno nuevo cada vez.
        assertThat(resolver.resolve(request("198.51.100.9", "5.6.7.8"))).isEqualTo("198.51.100.9");
    }

    @Test
    @DisplayName("Con un proxy confiable se toma el último salto, no el primero")
    void behindATrustedProxyTheLastHopWins() {
        ClientIpResolver resolver = new ClientIpResolver("203.0.113.7");

        // Esto es exactamente lo que produce nginx con su receta estándar
        // (proxy_add_x_forwarded_for) cuando el cliente miente: lo que mandó él, y detrás su IP real.
        // Tomar el extremo izquierdo —lo que se hacía— devuelve la mentira.
        assertThat(resolver.resolve(request("203.0.113.7", "1.2.3.4, 198.51.100.9")))
                .isEqualTo("198.51.100.9");
    }

    @Test
    @DisplayName("Si quien llega al socket no es un proxy confiable, la cabecera se ignora")
    void aDirectCallerCannotUseTheHeader() {
        ClientIpResolver resolver = new ClientIpResolver("203.0.113.7");

        // Alguien que alcanza la aplicación directamente y se inventa la cadena entera.
        assertThat(resolver.resolve(request("198.51.100.50", "1.2.3.4, 203.0.113.7")))
                .isEqualTo("198.51.100.50");
    }

    @Test
    @DisplayName("Con varios saltos confiables se devuelve el primer no confiable por la derecha")
    void theChainIsWalkedFromTheRight() {
        ClientIpResolver resolver = new ClientIpResolver("203.0.113.7, 203.0.113.8");

        assertThat(resolver.resolve(request("203.0.113.8", "1.2.3.4, 198.51.100.9, 203.0.113.7")))
                .isEqualTo("198.51.100.9");
    }

    @Test
    @DisplayName("Se admite CIDR, y una cadena toda de proxies cae al socket")
    void cidrEntriesAndAnAllProxyChain() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8");

        assertThat(resolver.resolve(request("10.1.2.3", "198.51.100.9"))).isEqualTo("198.51.100.9");
        // Nada que distinguir: no hay cliente en la cadena.
        assertThat(resolver.resolve(request("10.1.2.3", "10.4.5.6"))).isEqualTo("10.1.2.3");
    }

    @Test
    @DisplayName("Una entrada que no es una dirección no se convierte en clave de contador")
    void garbageInTheHeaderFallsBackToTheSocket() {
        ClientIpResolver resolver = new ClientIpResolver("203.0.113.7");

        // Si se aceptara, el atacante volvería a elegir su contador con cualquier texto.
        assertThat(resolver.resolve(request("203.0.113.7", "no-es-una-ip"))).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve(request("203.0.113.7", "' OR 1=1"))).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("Sin cabecera, y con una lista mal escrita, se usa la dirección del socket")
    void noHeaderAndAMisconfiguredList() {
        assertThat(new ClientIpResolver("203.0.113.7").resolve(request("203.0.113.7", null)))
                .isEqualTo("203.0.113.7");
        // Una entrada inválida en la propiedad no puede valer como "confío en todo".
        assertThat(new ClientIpResolver("no-es-un-cidr").resolve(request("198.51.100.9", "1.2.3.4")))
                .isEqualTo("198.51.100.9");
    }

    // --- POS-6 ------------------------------------------------------------------------------------

    @Test
    @DisplayName("POS-6: un proxy confiable IPv4 no hace confiable a una IPv6 que empiece con el mismo byte")
    void aTrustedIpv4RangeDoesNotTrustAnIpv6Peer() {
        // Con IpAddressMatcher de Spring, 10.0.0.0/8 aceptaba a00::1 como proxy confiable, y entonces se creía la
        // cabecera que mandara.
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8");

        assertThat(resolver.resolve(request("a00::1", "198.51.100.9"))).isEqualTo("a00::1");
        assertThat(resolver.resolveStrict(request("a00::1", "198.51.100.9"))).isEmpty();
    }

    @Test
    @DisplayName("POS-6: sin proxy de por medio, la dirección estricta es la del socket")
    void theStrictAddressOfADirectCallIsTheSocket() {
        ClientIpResolver resolver = new ClientIpResolver("203.0.113.7");

        assertThat(resolver.resolveStrict(request("10.0.0.5", null))).contains("10.0.0.5");
    }

    @Test
    @DisplayName("POS-6: una cabecera de reenvío desde quien no es proxy confiable deja la dirección sin saber")
    void aForwardingHeaderFromAnUntrustedPeerIsUnknown() {
        // Detrás de un proxy que nadie declaró, el socket es la dirección privada del proxy: tomarla dejaría pasar a
        // todo Internet por la lista de redes de la plataforma.
        ClientIpResolver resolver = new ClientIpResolver("");

        assertThat(resolver.resolveStrict(request("10.0.0.5", "198.51.100.9"))).isEmpty();
        MockHttpServletRequest forwarded = request("10.0.0.5", null);
        forwarded.addHeader("Forwarded", "for=198.51.100.9");
        assertThat(resolver.resolveStrict(forwarded)).isEmpty();
        MockHttpServletRequest realIp = request("10.0.0.5", null);
        realIp.addHeader("X-Real-IP", "198.51.100.9");
        assertThat(resolver.resolveStrict(realIp)).isEmpty();
    }

    @Test
    @DisplayName("POS-6: detrás de un proxy confiable, la dirección estricta es el primer salto no confiable")
    void behindATrustedProxyTheStrictAddressIsTheClient() {
        ClientIpResolver resolver = new ClientIpResolver("203.0.113.7");

        assertThat(resolver.resolveStrict(request("203.0.113.7", "1.2.3.4, 198.51.100.9"))).contains("198.51.100.9");
    }

    @Test
    @DisplayName("POS-6: donde resolve cae en el socket, la dirección estricta queda sin saber")
    void whereResolveFallsBackTheStrictAddressIsUnknown() {
        ClientIpResolver resolver = new ClientIpResolver("203.0.113.7");

        // Un proxy confiable que no dice de quién viene la petición.
        assertThat(resolver.resolveStrict(request("203.0.113.7", null))).isEmpty();
        // Un salto que no es una dirección.
        assertThat(resolver.resolveStrict(request("203.0.113.7", "no-es-una-ip"))).isEmpty();
        assertThat(resolver.resolveStrict(request("203.0.113.7", "198.51.100.9, , 203.0.113.7"))).isEmpty();
    }

    @Test
    @DisplayName("POS-6: una cadena toda de proxies confiables nació en el del extremo izquierdo")
    void anAllProxyChainStartsAtTheLeftmostHop() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8");

        assertThat(resolver.resolveStrict(request("10.1.2.3", "10.4.5.6, 10.7.8.9"))).contains("10.4.5.6");
    }

    private MockHttpServletRequest request(String socketAddress, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(socketAddress);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }
}
