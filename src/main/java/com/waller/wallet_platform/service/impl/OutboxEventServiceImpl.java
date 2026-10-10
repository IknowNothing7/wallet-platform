package com.waller.wallet_platform.service.impl;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.waller.wallet_platform.mapper.OutboxEventMapper;
import com.waller.wallet_platform.model.dto.OutboxEventDto;
import com.waller.wallet_platform.model.entites.OutboxEvent;
import com.waller.wallet_platform.model.enums.OutboxStatus;
import com.waller.wallet_platform.model.response.PageResponse;
import com.waller.wallet_platform.repositories.OutboxRepository;
import com.waller.wallet_platform.service.OutboxEventService;
import com.waller.wallet_platform.utils.ApiUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Reads and retries are admin-only (enforced in the controller); record() is called by the business services
@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxEventServiceImpl implements OutboxEventService {

    private static final String EVENT_NOT_FOUND = "Outbox event not found";

    // Jackson 2, to match OutboxEvent.payload; Boot 4 only auto-configures a Jackson 3 mapper
    private static final ObjectMapper PAYLOAD_MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

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
