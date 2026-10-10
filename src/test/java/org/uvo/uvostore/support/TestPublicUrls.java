package org.uvo.uvostore.support;

import org.uvo.uvostore.service.url.StorePublicUrlResolver;

/**
 * PROD-04. Un {@link StorePublicUrlResolver} para los tests que construyen un servicio con {@code new} en
 * vez de pedirlo al contexto.
 *
 * <p>Las plantillas son las mismas que surefire pone en {@code pom.xml}, para que un aserto sobre un enlace
 * diga lo mismo construido por Spring o a mano, y el host coincide con el que usa
 * {@code IntegrationTestSupport.hostHeader(store)}: {@code <slug>.localhost}.
 */
public final class TestPublicUrls {

    private TestPublicUrls() {
    }

    public static StorePublicUrlResolver resolver() {
        return new StorePublicUrlResolver("localhost", "http://{host}", "http://{host}");
    }
}
