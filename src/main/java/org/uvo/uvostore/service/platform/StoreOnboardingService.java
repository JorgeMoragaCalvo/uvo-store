package org.uvo.uvostore.service.platform;

public interface StoreOnboardingService {
    StoreOnboardingResponse createStore(StoreOnboardingCommand command);

    /** PROD-04: cambiar el dominio deja la verificación en NULL — un dominio nuevo no la hereda. */
    StoreOnboardingResponse updateDomain(Long storeId, String domain);

    /**
     * Quita el dominio propio (y su verificación): la tienda queda solo con su subdominio de plataforma y el
     * dominio se libera para otra tienda. Sin dominio no hace nada ni deja auditoría.
     */
    StoreOnboardingResponse clearDomain(Long storeId);

    /**
     * PROD-04. Marca (o desmarca) el dominio propio de la tienda como comprobado, que es lo que permite
     * usarlo en los enlaces de salida. Registra la comprobación del operador; no sondea DNS.
     */
    StoreOnboardingResponse setDomainVerified(Long storeId, boolean verified);
}
