package com.waller.wallet_platform.outbox.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.common.util.ApiUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.waller.wallet_platform.outbox.dto.OutboxEventDto;
import com.waller.wallet_platform.outbox.entity.OutboxEvent;
import com.waller.wallet_platform.outbox.entity.OutboxStatus;
import com.waller.wallet_platform.outbox.mapper.OutboxEventMapper;
import com.waller.wallet_platform.outbox.repository.OutboxRepository;

// Reads and retries are admin-only (enforced in the controller); record() is called by the business services
@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxEventServiceImpl implements OutboxEventService {

    private static final String EVENT_NOT_FOUND = "Outbox event not found";

    // Jackson 3's defaults already write java.time values as ISO-8601 strings, as the contracts require. A mapper of
    // its own rather than Boot's, so web settings (e.g. spring.jackson.*) can't change the event format.
    private static final JsonMapper PAYLOAD_MAPPER = JsonMapper.builder().build();

    private final OutboxRepository outboxRepository;
    private final OutboxEventMapper outboxEventMapper;

    @Override
    @Transactional(readOnly = true)
    public OutboxEventDto getEvent(Long id) {
        return outboxRepository.findById(id)
                .map(outboxEventMapper::toDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, EVENT_NOT_FOUND));
    }

    @Override
    @Transactional
    public OutboxEventDto retryEvent(Long id) {
        if (outboxRepository.resetFailedToPending(id) == 0) {
            OutboxEvent event = outboxRepository.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, EVENT_NOT_FOUND));
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only FAILED events can be retried; this one is " + event.getStatus());
        }
        log.info("Outbox event {} reset to PENDING by {}", id, ApiUtils.getCurrentUsername());
        return getEvent(id);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<OutboxEventDto> getEvents(OutboxStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return PageResponse.of(
                status == null
                        ? outboxRepository.findAllByOrderByCreatedAtDescIdDesc(pageable)
                        : outboxRepository.findByStatusOrderByCreatedAtDescIdDesc(status, pageable),
                outboxEventMapper::toDto);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String aggregateType, Long aggregateId, String eventType, Object payload) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setPayload(PAYLOAD_MAPPER.valueToTree(payload));
        outboxRepository.save(event);
    }

}
