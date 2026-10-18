package com.waller.notification_service.notification.service;

/** User-facing title plus a body format string whose placeholders are filled per event. */
public record NotificationTemplate(String title, String body) {

    public NotificationDraft draft(long accountId, Object... args) {
        return new NotificationDraft(accountId, title, body.formatted(args));
    }
}
