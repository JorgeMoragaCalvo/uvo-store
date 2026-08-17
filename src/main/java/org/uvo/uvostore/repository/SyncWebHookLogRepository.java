package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.uvo.uvostore.entity.pos.SyncWebhookLog;
import org.uvo.uvostore.entity.pos.enums.WebhookStatus;

import java.util.List;

public interface SyncWebHookLogRepository extends JpaRepository<SyncWebhookLog, Long> {

    List<SyncWebhookLog> findByCompanyIdAndEventTypeAndStatus(Long companyId, String eventType, WebhookStatus status);
    List<SyncWebhookLog> findTop50ByCompanyIdOrderByCreatedAtDesc(Long companyId);
}
