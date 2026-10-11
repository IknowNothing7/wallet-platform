package com.waller.notification_service.notification.dto;

import java.io.Serializable;

public record UnreadCountDto(long unread) implements Serializable {
}
