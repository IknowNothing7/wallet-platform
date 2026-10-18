package com.waller.wallet_platform.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.outbox.dto.OutboxEventDto;
import com.waller.wallet_platform.outbox.entity.OutboxEvent;
import com.waller.wallet_platform.outbox.entity.OutboxStatus;
import com.waller.wallet_platform.outbox.mapper.OutboxEventMapper;
import com.waller.wallet_platform.outbox.repository.OutboxRepository;
import static com.waller.wallet_platform.support.TestData.outboxEvent;

@ExtendWith(MockitoExtension.class)
class OutboxEventServiceImplTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private OutboxEventMapper outboxEventMapper;

    @InjectMocks
    private OutboxEventServiceImpl outboxEventService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void retryEvent_failedEvent_isResetAndReturned() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin", null));
        OutboxEvent event = event(1L, OutboxStatus.PENDING);
        OutboxEventDto dto = OutboxEventDto.builder().id(1L).status(OutboxStatus.PENDING).build();
        when(outboxRepository.resetFailedToPending(1L)).thenReturn(1);
        when(outboxRepository.findById(1L)).thenReturn(Optional.of(event));
        when(outboxEventMapper.toDto(event)).thenReturn(dto);

        assertThat(outboxEventService.retryEvent(1L)).isEqualTo(dto);
    }

    @Test
    void retryEvent_unknownEvent_is404() {
        when(outboxRepository.resetFailedToPending(1L)).thenReturn(0);
        when(outboxRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> outboxEventService.retryEvent(1L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void retryEvent_eventNotFailed_is409() {
        when(outboxRepository.resetFailedToPending(1L)).thenReturn(0);
        when(outboxRepository.findById(1L)).thenReturn(Optional.of(event(1L, OutboxStatus.PUBLISHED)));

        assertThatThrownBy(() -> outboxEventService.retryEvent(1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getReason()).contains("PUBLISHED");
                });
    }

    @Test
    void getEvents_withoutStatus_returnsAllStatuses() {
        OutboxEvent event = event(1L, OutboxStatus.PUBLISHED);
        PageRequest pageable = PageRequest.of(0, 20);
        when(outboxRepository.findAllByOrderByCreatedAtDescIdDesc(pageable))
                .thenReturn(new PageImpl<>(List.of(event), pageable, 1));
        when(outboxEventMapper.toDto(event)).thenReturn(OutboxEventDto.builder().id(1L).build());

        PageResponse<OutboxEventDto> result = outboxEventService.getEvents(null, 0, 20);

        assertThat(result.content()).extracting(OutboxEventDto::id).containsExactly(1L);
        verify(outboxRepository, never()).findByStatusOrderByCreatedAtDescIdDesc(any(), any());
    }

    @Test
    void getEvents_withStatus_filtersByStatus() {
        OutboxEvent event = event(2L, OutboxStatus.FAILED);
        PageRequest pageable = PageRequest.of(1, 5);
        when(outboxRepository.findByStatusOrderByCreatedAtDescIdDesc(OutboxStatus.FAILED, pageable))
                .thenReturn(new PageImpl<>(List.of(event), pageable, 6));
        when(outboxEventMapper.toDto(event)).thenReturn(OutboxEventDto.builder().id(2L).build());

        PageResponse<OutboxEventDto> result = outboxEventService.getEvents(OutboxStatus.FAILED, 1, 5);

        assertThat(result.content()).extracting(OutboxEventDto::id).containsExactly(2L);
        verify(outboxRepository, never()).findAllByOrderByCreatedAtDescIdDesc(any());
    }

    private static OutboxEvent event(Long id, OutboxStatus status) {
        OutboxEvent event = outboxEvent("transfer", 42L, "TransferCompleted");
        event.setId(id);
        event.setStatus(status);
        return event;
    }
}
