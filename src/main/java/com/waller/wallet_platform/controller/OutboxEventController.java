package com.waller.wallet_platform.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.waller.wallet_platform.model.dto.OutboxEventDto;
import com.waller.wallet_platform.model.enums.OutboxStatus;
import com.waller.wallet_platform.model.response.ApiResponse;
import com.waller.wallet_platform.model.response.PageResponse;
import com.waller.wallet_platform.service.OutboxEventService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/outbox")
@PreAuthorize("hasRole('ADMIN')")
public class OutboxEventController {

    private final OutboxEventService outboxEventService;

    @GetMapping("/{id}")
    public ApiResponse<OutboxEventDto> getEvent(@PathVariable Long id) {
        return ApiResponse.ok(outboxEventService.getEvent(id));
    }

    // 409 if the event isn't FAILED
    @PostMapping("/{id}/retry")
    public ApiResponse<OutboxEventDto> retryEvent(@PathVariable Long id) {
        return ApiResponse.ok(outboxEventService.retryEvent(id));
    }

    // e.g. /outbox?status=FAILED
    @GetMapping
    public ApiResponse<PageResponse<OutboxEventDto>> getEvents(
            @RequestParam(required = false) OutboxStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(outboxEventService.getEvents(status, page, size));
    }

}
