package org.uvo.uvostore.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * POS-6. El comparador de rangos de IP que reemplaza a {@code IpAddressMatcher} de Spring Security, que en la 7.1.0
 * no distingue IPv4 de IPv6. Cada caso de "familia cruzada" es uno que Spring acertaba mal o con una excepción.
 */
class IpRangesTest {

    @Test
    @DisplayName("Una IPv4 nunca cae en un rango IPv6, ni al revés")
    void theAddressFamilyMustMatch() {
        // Spring: 10.0.0.0/8 aceptaba a00::1, porque solo comparaba el primer byte.
        assertThat(IpRanges.parseStrict("10.0.0.0/8").matches("a00::1")).isFalse();
        // Spring: 32.0.0.0/8 abría todo 2000::/8, que es espacio IPv6 público.
        assertThat(IpRanges.parseStrict("32.0.0.0/8").matches("2001:db8::1")).isFalse();
        // Spring: ::1/128 contra 0.0.0.0 lanzaba ArrayIndexOutOfBoundsException, un 500 desde un filtro.
        assertThat(IpRanges.parseStrict("::1/128").matches("0.0.0.0")).isFalse();
        assertThat(IpRanges.parseStrict("fc00::/7").matches("252.0.0.1")).isFalse();
    }

    @Test
    @DisplayName("Los rangos de siempre aciertan en su familia")
    void rangesMatchWithinTheirFamily() {
        IpRanges ranges = IpRanges.parseStrict("127.0.0.0/8, ::1/128, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, fc00::/7");

        assertThat(ranges.matches("127.0.0.1")).isTrue();
        assertThat(ranges.matches("10.200.3.4")).isTrue();
        assertThat(ranges.matches("172.31.255.255")).isTrue();
        assertThat(ranges.matches("172.32.0.1")).isFalse();
        assertThat(ranges.matches("192.168.1.10")).isTrue();
        assertThat(ranges.matches("203.0.113.9")).isFalse();
        // Las dos formas en que Tomcat puede escribir el loopback IPv6.
        assertThat(ranges.matches("::1")).isTrue();
        assertThat(ranges.matches("0:0:0:0:0:0:0:1")).isTrue();
        assertThat(ranges.matches("fd12:3456::1")).isTrue();
        assertThat(ranges.matches("2001:db8::1")).isFalse();
        // Una IPv6 con un IPv4 incrustado es ese IPv4.
        assertThat(ranges.matches("::ffff:10.0.0.1")).isTrue();
        // La zona de una dirección de enlace local no cuenta.
        assertThat(IpRanges.parseStrict("fe80::/10").matches("fe80::1%eth0")).isTrue();
    }

    @Test
    @DisplayName("Una dirección suelta es un rango de una sola dirección, y el prefijo no tiene por qué caer en un octeto")
    void singleAddressesAndOddPrefixes() {
        assertThat(IpRanges.parseStrict("203.0.113.7").matches("203.0.113.7")).isTrue();
        assertThat(IpRanges.parseStrict("203.0.113.7").matches("203.0.113.8")).isFalse();
        assertThat(IpRanges.parseStrict("10.0.0.0/9").matches("10.127.0.1")).isTrue();
        assertThat(IpRanges.parseStrict("10.0.0.0/9").matches("10.128.0.1")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10", "10.0.0", "256.0.0.1", "10.0.0.0/33", "::1/129", "10.0.0.0/x", "localhost",
            "pos.internal", "10.0.0.0/8/8", "gg::1"})
    @DisplayName("Lo que no es una IP ni un CIDR no se acepta, y nunca se resuelve como nombre")
    void invalidEntriesAreRejected(String entry) {
        // Spring leía "10" como 0.0.0.10.
        assertThatThrownBy(() -> IpRanges.parseStrict(entry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(entry);
    }

    @Test
    @DisplayName("La lista indulgente descarta lo inválido, avisa, y conserva el resto")
    void theLenientListKeepsTheValidEntries() {
        List<String> rejected = new ArrayList<>();

        IpRanges ranges = IpRanges.parseLenient("10.0.0.0/8, no-es-un-cidr, 192.168.0.0/16", rejected::add);

        assertThat(rejected).containsExactly("no-es-un-cidr");
        assertThat(ranges.matches("10.1.1.1")).isTrue();
        assertThat(ranges.matches("192.168.1.1")).isTrue();
        assertThat(ranges.describe()).isEqualTo("10.0.0.0/8, 192.168.0.0/16");
    }

    @Test
    @DisplayName("Lo que no es una dirección nunca coincide ni lanza")
    void garbageNeverMatches() {
        IpRanges ranges = IpRanges.parseStrict("0.0.0.0/0, ::/0");

        assertThat(ranges.matches("no-es-una-ip")).isFalse();
        assertThat(ranges.matches("")).isFalse();
        assertThat(ranges.matches(null)).isFalse();
        assertThat(ranges.matches("10.0.0.0/8")).isFalse();
    }

    @Test
    @DisplayName("Un /0 abarca todo, y una lista vacía no deja pasar nada")
    void everythingAndNothing() {
        assertThat(IpRanges.parseStrict("10.0.0.0/8, 0.0.0.0/0").coversEverything()).isTrue();
        assertThat(IpRanges.parseStrict("::/0").coversEverything()).isTrue();
        assertThat(IpRanges.parseStrict("10.0.0.0/8").coversEverything()).isFalse();

        IpRanges empty = IpRanges.parseStrict("  ");
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.matches("127.0.0.1")).isFalse();
    }
}
