package com.waller.notification_service.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import com.waller.notification_service.event.entity.AccountOwner;
import com.waller.notification_service.event.entity.ProcessedEvent;
import com.waller.notification_service.event.repository.AccountOwnerRepository;
import com.waller.notification_service.event.repository.ProcessedEventRepository;
import com.waller.notification_service.notification.service.NotificationDraft;
import com.waller.notification_service.notification.service.NotificationService;
import com.waller.notification_service.notification.service.NotificationTemplates;

@ExtendWith(MockitoExtension.class)
class WalletEventHandlerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private AccountOwnerRepository accountOwnerRepository;

    @Mock
    private NotificationTemplates notificationTemplates;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private WalletEventHandler handler;

    @Test
    void alreadyProcessedEvent_isSkipped() {
        WalletEvent event = event(1L, "TransferCompleted", "{}");
        when(processedEventRepository.existsById(1L)).thenReturn(true);

        handler.handle(event);

        verifyNoInteractions(notificationTemplates, notificationService, accountOwnerRepository);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void accountCreated_storesOwnerWithoutNotifying() {
        WalletEvent event = event(1L, "AccountCreated", "{\"id\": 10, \"userId\": 77}");

        handler.handle(event);

        ArgumentCaptor<AccountOwner> owner = ArgumentCaptor.forClass(AccountOwner.class);
        verify(accountOwnerRepository).save(owner.capture());
        assertThat(owner.getValue().getAccountId()).isEqualTo(10L);
        assertThat(owner.getValue().getUserId()).isEqualTo(77L);
        verifyNoInteractions(notificationTemplates, notificationService);
        verifyMarkedProcessed(1L, "AccountCreated");
    }

    @Test
    void eachDraft_goesToTheOwnerOfItsAccount() {
        WalletEvent event = event(2L, "TransferCompleted", "{}");
        NotificationDraft sent = new NotificationDraft(10L, "Money sent", "You sent 1.00 USD");
        NotificationDraft received = new NotificationDraft(20L, "Money received", "You received 1.00 USD");
        when(notificationTemplates.render(event)).thenReturn(List.of(sent, received));
        when(accountOwnerRepository.findById(10L)).thenReturn(Optional.of(new AccountOwner(10L, 100L)));
        when(accountOwnerRepository.findById(20L)).thenReturn(Optional.of(new AccountOwner(20L, 200L)));

        handler.handle(event);

        verify(notificationService).create(100L, event, sent);
        verify(notificationService).create(200L, event, received);
        verifyMarkedProcessed(2L, "TransferCompleted");
    }

    @Test
    void unknownAccount_throwsAndLeavesEventUnprocessedSoItIsRetried() {
        WalletEvent event = event(3L, "DepositCreated", "{}");
        when(notificationTemplates.render(event)).thenReturn(List.of(new NotificationDraft(10L, "t", "b")));
        when(accountOwnerRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(event))
                .isInstanceOf(UnknownAccountException.class)
                .hasMessageContaining("10");

        verify(notificationService, never()).create(anyLong(), any(), any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void eventWithoutTemplate_isStillMarkedProcessed() {
        WalletEvent event = event(4L, "SomethingElse", "{}");
        when(notificationTemplates.render(event)).thenReturn(List.of());

        handler.handle(event);

        verifyNoInteractions(notificationService);
        verifyMarkedProcessed(4L, "SomethingElse");
    }

    private void verifyMarkedProcessed(long eventId, String eventType) {
        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(processed.capture());
        assertThat(processed.getValue().getEventId()).isEqualTo(eventId);
        assertThat(processed.getValue().getEventType()).isEqualTo(eventType);
    }

    private static WalletEvent event(long eventId, String eventType, String payload) {
        return new WalletEvent(eventId, eventType, "test", 1L, JSON.readTree(payload));
    }
}
