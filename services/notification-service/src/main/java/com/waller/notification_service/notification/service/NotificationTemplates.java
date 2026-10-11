package com.waller.notification_service.notification.service;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;
import com.waller.notification_service.event.service.JsonNodeFields;
import com.waller.notification_service.event.service.WalletEvent;

/** Maps wallet event types to user-facing texts; event types without a template produce nothing. */
@Component
public class NotificationTemplates {

    public List<NotificationDraft> render(WalletEvent event) {
        JsonNodeFields p = new JsonNodeFields(event.payload());
        return switch (event.eventType()) {
            case "DepositCreated" -> List.of(new NotificationDraft(
                    p.longValue("accountId"), "Top-up started", "Top-up of " + money(p) + " is being processed"));
            case "TransferCreated" -> List.of(
                    new NotificationDraft(p.longValue("fromAccountId"), "Money sent", "You sent " + money(p)),
                    new NotificationDraft(p.longValue("toAccountId"), "Money received", "You received " + money(p)));
            case "HoldCreated" -> List.of(new NotificationDraft(
                    p.longValue("accountId"), "Funds reserved", money(p) + " reserved on your account"));
            case "HoldCaptured" -> List.of(new NotificationDraft(
                    p.longValue("accountId"), "Payment completed", money(p) + " charged"));
            case "HoldReleased", "HoldExpired" -> List.of(new NotificationDraft(
                    p.longValue("accountId"), "Reservation released", money(p) + " is available again"));
            // TODO: DepositSucceeded / DepositFailed once wallet-service emits them from the bank webhook
            default -> List.of();
        };
    }

    // Amounts are in minor units
    private static String money(JsonNodeFields p) {
        return BigDecimal.valueOf(p.longValue("amount"), 2).toPlainString() + " " + p.text("currency");
    }
}
