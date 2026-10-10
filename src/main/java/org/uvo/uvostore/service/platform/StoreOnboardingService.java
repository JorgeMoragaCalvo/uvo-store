package org.uvo.uvostore.service.platform;

public interface StoreOnboardingService {
    StoreOnboardingResponse createStore(StoreOnboardingCommand command);

    /** PROD-04: cambiar el dominio deja la verificación en NULL — un dominio nuevo no la hereda. */
    StoreOnboardingResponse updateDomain(Long storeId, String domain);

    /**
     * PROD-04. Marca (o desmarca) el dominio propio de la tienda como comprobado, que es lo que permite
     * usarlo en los enlaces de salida. Registra la comprobación del operador; no sondea DNS.
     */
    StoreOnboardingResponse setDomainVerified(Long storeId, boolean verified);
}
