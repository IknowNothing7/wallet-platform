package com.waller.notification_service.notification.service;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;
import com.waller.notification_service.event.service.JsonNodeFields;
import com.waller.notification_service.event.service.WalletEvent;

/** Maps wallet event types to user-facing texts; event types without a template produce nothing. */
@Component
public class NotificationTemplates {

    private static final NotificationTemplate DEPOSIT_CREATED =
            new NotificationTemplate("Top-up started", "Top-up of %s is being processed");
    private static final NotificationTemplate DEPOSIT_SUCCEEDED =
            new NotificationTemplate("Top-up completed", "Top-up of %s completed");
    private static final NotificationTemplate DEPOSIT_FAILED =
            new NotificationTemplate("Top-up failed", "Top-up of %s was declined by the bank");
    private static final NotificationTemplate DEPOSIT_EXPIRED =
            new NotificationTemplate("Top-up timed out", "Top-up of %s was not confirmed by the bank in time");
    private static final NotificationTemplate TRANSFER_SENT =
            new NotificationTemplate("Money sent", "You sent %s");
    private static final NotificationTemplate TRANSFER_RECEIVED =
            new NotificationTemplate("Money received", "You received %s");
    private static final NotificationTemplate HOLD_CREATED =
            new NotificationTemplate("Funds reserved", "%s reserved on your account");
    private static final NotificationTemplate HOLD_CAPTURED =
            new NotificationTemplate("Payment completed", "%s charged");
    private static final NotificationTemplate HOLD_RELEASED =
            new NotificationTemplate("Reservation released", "%s is available again");

    public List<NotificationDraft> render(WalletEvent event) {
        JsonNodeFields p = new JsonNodeFields(event.payload());
        return switch (event.eventType()) {
            case "DepositCreated" -> List.of(DEPOSIT_CREATED.draft(p.longValue("accountId"), money(p)));
            case "DepositSucceeded" -> List.of(DEPOSIT_SUCCEEDED.draft(p.longValue("accountId"), money(p)));
            // failureReason is left out on purpose: it can hold the raw bank response
            case "DepositFailed" -> List.of(DEPOSIT_FAILED.draft(p.longValue("accountId"), money(p)));
            case "DepositExpired" -> List.of(DEPOSIT_EXPIRED.draft(p.longValue("accountId"), money(p)));
            // Only once booked: transfers are synchronous, so there is no earlier event worth telling anyone about
            case "TransferCompleted" -> List.of(
                    TRANSFER_SENT.draft(p.longValue("fromAccountId"), money(p)),
                    TRANSFER_RECEIVED.draft(p.longValue("toAccountId"), money(p)));
            case "HoldCreated" -> List.of(HOLD_CREATED.draft(p.longValue("accountId"), money(p)));
            case "HoldCaptured" -> List.of(HOLD_CAPTURED.draft(p.longValue("accountId"), money(p)));
            case "HoldReleased", "HoldExpired" -> List.of(HOLD_RELEASED.draft(p.longValue("accountId"), money(p)));
            default -> List.of();
        };
    }

    private static String money(JsonNodeFields p) {
        return BigDecimal.valueOf(p.longValue("amount"), 2).toPlainString() + " " + p.text("currency");
    }
}
