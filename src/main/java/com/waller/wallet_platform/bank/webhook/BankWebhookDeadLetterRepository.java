package com.waller.wallet_platform.bank.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BankWebhookDeadLetterRepository extends JpaRepository<BankWebhookDeadLetter, Long> {
}
