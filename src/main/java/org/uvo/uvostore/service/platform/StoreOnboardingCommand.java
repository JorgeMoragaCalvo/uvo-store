package org.uvo.uvostore.service.platform;

public record StoreOnboardingCommand(
        String slug,
        String domain,
        String storeName,
        String adminName,
        String adminEmail,
        String adminPassword
) {
}
