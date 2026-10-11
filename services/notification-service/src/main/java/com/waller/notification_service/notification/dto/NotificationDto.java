package com.waller.notification_service.notification.dto;

import java.io.Serializable;
import java.time.Instant;
import com.waller.notification_service.notification.entity.Notification;

public record NotificationDto(
        Long id,
        Instant createdAt,
        String eventType,
        String title,
        String body,
        Instant readAt

) implements Serializable {

    public static NotificationDto of(Notification notification) {
        return new NotificationDto(
                notification.getId(),
                notification.getCreatedAt(),
                notification.getEventType(),
                notification.getTitle(),
                notification.getBody(),
                notification.getReadAt());
    }
}
