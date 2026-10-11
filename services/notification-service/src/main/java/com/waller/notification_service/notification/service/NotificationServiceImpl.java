package com.waller.notification_service.notification.service;

import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;
import com.waller.notification_service.common.web.PageResponse;
import com.waller.notification_service.event.service.WalletEvent;
import com.waller.notification_service.notification.dto.NotificationDto;
import com.waller.notification_service.notification.dto.UnreadCountDto;
import com.waller.notification_service.notification.entity.Notification;
import com.waller.notification_service.notification.repository.NotificationRepository;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private static final String NOTIFICATION_NOT_FOUND = "Notification not found";

    private final NotificationRepository notificationRepository;

    @Override
    @Transactional
    public void create(long userId, WalletEvent event, NotificationDraft draft) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setEventId(event.eventId());
        notification.setEventType(event.eventType());
        notification.setTitle(draft.title());
        notification.setBody(draft.body());
        notificationRepository.save(notification);
        // TODO: push channel (email / web push / SSE) goes here
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationDto> getNotifications(long userId, int page, int size) {
        return PageResponse.of(
                notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size)),
                NotificationDto::of);
    }

    @Override
    @Transactional(readOnly = true)
    public UnreadCountDto getUnreadCount(long userId) {
        return new UnreadCountDto(notificationRepository.countByUserIdAndReadAtIsNull(userId));
    }

    @Override
    @Transactional
    public NotificationDto markRead(long userId, long notificationId) {
        // Scoped by user: someone else's id is reported as not found
        Notification notification = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOTIFICATION_NOT_FOUND));
        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
        }
        return NotificationDto.of(notification);
    }

    @Override
    @Transactional
    public void markAllRead(long userId) {
        notificationRepository.markAllRead(userId, Instant.now());
    }
}
