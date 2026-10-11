package com.waller.notification_service.notification.service;

/** A notification before its recipient is resolved: events name accounts, not users. */
public record NotificationDraft(long accountId, String title, String body) {
}
