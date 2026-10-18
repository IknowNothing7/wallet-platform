package com.waller.notification_service.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import com.waller.notification_service.event.service.WalletEvent;

class NotificationTemplatesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final NotificationTemplates templates = new NotificationTemplates();

    @Test
    void depositCreated_formatsMinorUnitsAsMoney() {
        assertThat(templates.render(event("DepositCreated", "{\"accountId\": 10, \"amount\": 12345, \"currency\": \"USD\"}")))
                .extracting(NotificationDraft::accountId, NotificationDraft::title, NotificationDraft::body)
                .containsExactly(tuple(10L, "Top-up started", "Top-up of 123.45 USD is being processed"));
    }

    @ParameterizedTest
    @CsvSource({
            "DepositSucceeded, Top-up completed, Top-up of 15.00 EUR completed",
            "DepositFailed, Top-up failed, Top-up of 15.00 EUR was declined by the bank",
            "DepositExpired, Top-up timed out, Top-up of 15.00 EUR was not confirmed by the bank in time"
    })
    void depositOutcome_notifiesAccountOwner(String eventType, String title, String body) {
        String payload = "{\"accountId\": 7, \"amount\": 1500, \"currency\": \"EUR\", \"failureReason\": \"Declined by issuer\"}";

        assertThat(templates.render(event(eventType, payload)))
                .extracting(NotificationDraft::accountId, NotificationDraft::title, NotificationDraft::body)
                .containsExactly(tuple(7L, title, body));
    }

    @Test
    void smallAmount_keepsLeadingZero() {
        assertThat(templates.render(event("HoldCaptured", "{\"accountId\": 10, \"amount\": 5, \"currency\": \"EUR\"}")))
                .extracting(NotificationDraft::body)
                .containsExactly("0.05 EUR charged");
    }

    @Test
    void transferCreated_notifiesSenderAndReceiver() {
        String payload = "{\"fromAccountId\": 10, \"toAccountId\": 20, \"amount\": 250, \"currency\": \"USD\"}";

        assertThat(templates.render(event("TransferCreated", payload)))
                .extracting(NotificationDraft::accountId, NotificationDraft::title, NotificationDraft::body)
                .containsExactly(
                        tuple(10L, "Money sent", "You sent 2.50 USD"),
                        tuple(20L, "Money received", "You received 2.50 USD"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HoldReleased", "HoldExpired"})
    void releasedAndExpiredHolds_shareOneTemplate(String eventType) {
        assertThat(templates.render(event(eventType, "{\"accountId\": 10, \"amount\": 100, \"currency\": \"USD\"}")))
                .extracting(NotificationDraft::title)
                .containsExactly("Reservation released");
    }

    @Test
    void unknownEventType_producesNothing() {
        assertThat(templates.render(event("UserCreated", "{}"))).isEmpty();
    }

    @Test
    void missingField_isRejectedAsMalformed() {
        assertThatThrownBy(() -> templates.render(event("DepositCreated", "{\"accountId\": 10, \"currency\": \"USD\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }

    private static WalletEvent event(String eventType, String payload) {
        return new WalletEvent(1L, eventType, "test", 1L, JSON.readTree(payload));
    }
}
