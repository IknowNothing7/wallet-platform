package com.waller.notification_service.notification.service;

import com.waller.notification_service.common.web.PageResponse;
import com.waller.notification_service.event.service.WalletEvent;
import com.waller.notification_service.notification.dto.NotificationDto;
import com.waller.notification_service.notification.dto.UnreadCountDto;

public interface NotificationService {

    void create(long userId, WalletEvent event, NotificationDraft draft);

    PageResponse<NotificationDto> getNotifications(long userId, int page, int size);

    UnreadCountDto getUnreadCount(long userId);

    NotificationDto markRead(long userId, long notificationId);

    void markAllRead(long userId);
}
