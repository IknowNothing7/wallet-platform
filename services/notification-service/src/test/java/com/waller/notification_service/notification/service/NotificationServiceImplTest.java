package com.waller.notification_service.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.waller.notification_service.notification.dto.NotificationDto;
import com.waller.notification_service.notification.entity.Notification;
import com.waller.notification_service.notification.repository.NotificationRepository;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    private static final long USER_ID = 100L;

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    @Test
    void markRead_setsReadAtOnUnreadNotification() {
        Notification notification = notification(null);
        when(notificationRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(notification));

        NotificationDto result = notificationService.markRead(USER_ID, 1L);

        assertThat(result.readAt()).isNotNull();
        assertThat(notification.getReadAt()).isEqualTo(result.readAt());
    }

    @Test
    void markRead_keepsOriginalReadAt() {
        Instant firstRead = Instant.parse("2026-01-01T10:00:00Z");
        when(notificationRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(notification(firstRead)));

        assertThat(notificationService.markRead(USER_ID, 1L).readAt()).isEqualTo(firstRead);
    }

    @Test
    void markRead_someoneElsesNotification_answersLikeMissingOne() {
        // The repository is scoped by user, so another user's id simply isn't found
        when(notificationRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.markRead(USER_ID, 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("Notification not found");
    }

    private static Notification notification(Instant readAt) {
        Notification notification = new Notification();
        notification.setId(1L);
        notification.setUserId(USER_ID);
        notification.setReadAt(readAt);
        return notification;
    }
}
