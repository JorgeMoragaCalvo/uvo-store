package org.uvo.uvostore.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.StoreRepository;
import org.uvo.uvostore.service.url.StorePublicUrlResolver;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PROD-05a. Qué hosts resuelven a una tienda.
 *
 * <p>Unitario y con el repositorio simulado a propósito: lo que se afirma aquí no es qué devuelve la base
 * sino <b>si se le llega a preguntar</b>. Un host ajeno cuyo primer segmento coincide con una tienda real
 * devuelve vacío tanto si el resolver lo descarta como si no (cuando el repositorio no tiene esa tienda),
 * así que el único test que distingue las dos situaciones es el que verifica la consulta por slug.
 */
class StoreHostResolverTest {

    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final StoreHostResolver resolver = new StoreHostResolver(storeRepository,
            new StorePublicUrlResolver("uvostore.cl", "https://{host}", "https://{host}"));

    private static Store store(String slug, String domain) {
        return Store.builder().id(1L).name("Tienda").slug(slug).domain(domain).build();
    }

    @Test
    @DisplayName("<slug>.<dominio-plataforma> resuelve la tienda por su slug, con o sin puerto")
    void thePlatformSubdomainResolvesByItsSlug() {
        Store juan = store("juan", null);
        when(storeRepository.findByDomain(anyString())).thenReturn(Optional.empty());
        when(storeRepository.findBySlug("juan")).thenReturn(Optional.of(juan));

        assertThat(resolver.resolve("juan.uvostore.cl")).contains(juan);
        assertThat(resolver.resolve("juan.uvostore.cl:8443")).contains(juan);
    }

    @Test
    @DisplayName("Un host ajeno no resuelve la tienda cuyo slug es su primer segmento")
    void aForeignHostDoesNotResolveByItsFirstLabel() {
        // El defecto de PROD-05a: "juan.dominio-ajeno.com" resolvía la tienda "juan". Se deja la tienda
        // disponible en el repositorio para que, si el resolver preguntara, el resultado no fuera vacío.
        when(storeRepository.findByDomain(anyString())).thenReturn(Optional.empty());
        when(storeRepository.findBySlug("juan")).thenReturn(Optional.of(store("juan", null)));

        assertThat(resolver.resolve("juan.dominio-ajeno.com")).isEmpty();
        assertThat(resolver.resolve("juan.dominio-ajeno.com:8080")).isEmpty();
        assertThat(resolver.resolve("juan.uvostore.cl.dominio-ajeno.com")).isEmpty();

        verify(storeRepository, never()).findBySlug(anyString());
    }

    @Test
    @DisplayName("El dominio propio sigue entrando por coincidencia exacta")
    void aCustomDomainStillResolvesByExactMatch() {
        // Control, no mordida: el cambio no toca esta ruta y el test existe para que siga siendo así. El
        // dominio propio no es del dominio de plataforma, así que solo esta ruta puede admitirlo.
        Store juan = store("juan", "tiendadejuan.cl");
        when(storeRepository.findByDomain("tiendadejuan.cl")).thenReturn(Optional.of(juan));

        assertThat(resolver.resolve("tiendadejuan.cl")).contains(juan);
        assertThat(resolver.resolve("tiendadejuan.cl:8443")).contains(juan);
    }

    @Test
    @DisplayName("Sin host, vacío, o el dominio de plataforma sin subdominio: nada que resolver")
    void aMissingHostOrABarePlatformDomainResolvesNothing() {
        when(storeRepository.findByDomain(anyString())).thenReturn(Optional.empty());

        assertThat(resolver.resolve(null)).isEmpty();
        assertThat(resolver.resolve("  ")).isEmpty();
        assertThat(resolver.resolve("uvostore.cl")).isEmpty();

        verify(storeRepository, never()).findBySlug(anyString());
    }
}
