package org.uvo.uvostore.service.platform;

public record StoreOnboardingResponse(
        Long storeId,
        String storeName,
        String slug,
        String domain,
        Long adminUserId,
        String adminEmail
) {
}
