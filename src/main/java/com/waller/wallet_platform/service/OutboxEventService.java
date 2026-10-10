package com.waller.wallet_platform.service;

import com.waller.wallet_platform.model.dto.OutboxEventDto;
import com.waller.wallet_platform.model.enums.OutboxStatus;
import com.waller.wallet_platform.model.response.PageResponse;

// Operator view; events are written by the business flows and sent by the publisher
public interface OutboxEventService {

    /** Puts a FAILED event back to PENDING with attempts reset, so the publisher sends it again. */
    OutboxEventDto retryEvent(Long id);

    OutboxEventDto getEvent(Long id);

    /** Newest first; all statuses when {@code status} is null. */
    PageResponse<OutboxEventDto> getEvents(OutboxStatus status, int page, int size);

    /**
     * Stores a PENDING event for the publisher. Must run inside the caller's transaction,
     * so the event is committed together with the change it describes, or not at all.
     */
    void record(String aggregateType, Long aggregateId, String eventType, Object payload);

}
