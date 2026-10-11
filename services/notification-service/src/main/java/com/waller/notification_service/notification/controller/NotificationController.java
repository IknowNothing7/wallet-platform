package com.waller.notification_service.notification.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import com.waller.notification_service.common.web.ApiResponse;
import com.waller.notification_service.common.web.PageResponse;
import com.waller.notification_service.notification.dto.NotificationDto;
import com.waller.notification_service.notification.dto.UnreadCountDto;
import com.waller.notification_service.notification.service.NotificationService;

// Reached through the ingress as /api/notifications/*
@RestController
@RequiredArgsConstructor
@RequestMapping("/notifications")
public class NotificationController {

    // Claim name used by wallet-service's JwtTokenProvider
    private static final String USER_ID_CLAIM = "user_id";

    private final NotificationService notificationService;

    @GetMapping
    public ApiResponse<PageResponse<NotificationDto>> getNotifications(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(notificationService.getNotifications(userId(jwt), page, size));
    }

    @GetMapping("/unread-count")
    public ApiResponse<UnreadCountDto> getUnreadCount(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(notificationService.getUnreadCount(userId(jwt)));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<NotificationDto> markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") Long id) {
        return ApiResponse.ok(notificationService.markRead(userId(jwt), id));
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal Jwt jwt) {
        notificationService.markAllRead(userId(jwt));
    }

    private static long userId(Jwt jwt) {
        Number userId = jwt.getClaim(USER_ID_CLAIM);
        return userId.longValue();
    }
}
