package com.waller.notification_service.event.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.waller.notification_service.event.entity.AccountOwner;
import com.waller.notification_service.event.entity.ProcessedEvent;
import com.waller.notification_service.event.repository.AccountOwnerRepository;
import com.waller.notification_service.event.repository.ProcessedEventRepository;
import com.waller.notification_service.notification.service.NotificationDraft;
import com.waller.notification_service.notification.service.NotificationService;
import com.waller.notification_service.notification.service.NotificationTemplates;

@Service
@Slf4j
@RequiredArgsConstructor
public class WalletEventHandler {

    private static final String ACCOUNT_CREATED = "AccountCreated";

    private final ProcessedEventRepository processedEventRepository;
    private final AccountOwnerRepository accountOwnerRepository;
    private final NotificationTemplates notificationTemplates;
    private final NotificationService notificationService;

    /**
     * Kafka delivers at least once, so the inbox row and the notifications are written in one transaction:
     * either both exist and a redelivery is skipped, or neither does and the record is retried.
     */
    @Transactional
    public void handle(WalletEvent event) {
        if (processedEventRepository.existsById(event.eventId())) {
            log.debug("Skipping already processed event {}", event.eventId());
            return;
        }

        if (ACCOUNT_CREATED.equals(event.eventType())) {
            JsonNodeFields payload = new JsonNodeFields(event.payload());
            accountOwnerRepository.save(new AccountOwner(payload.longValue("id"), payload.longValue("userId")));
        } else {
            List<NotificationDraft> drafts = notificationTemplates.render(event);
            for (NotificationDraft draft : drafts) {
                long userId = accountOwnerRepository.findById(draft.accountId())
                        .map(AccountOwner::getUserId)
                        .orElseThrow(() -> new UnknownAccountException(draft.accountId()));
                notificationService.create(userId, event, draft);
            }
        }

        processedEventRepository.save(new ProcessedEvent(event.eventId(), event.eventType()));
    }
}
