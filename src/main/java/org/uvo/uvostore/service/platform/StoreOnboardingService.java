package org.uvo.uvostore.service.platform;

public interface StoreOnboardingService {
    StoreOnboardingResponse createStore(StoreOnboardingCommand command);
    StoreOnboardingResponse updateDomain(Long storeId, String domain);
}
