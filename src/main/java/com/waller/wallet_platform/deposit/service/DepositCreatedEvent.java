package com.waller.wallet_platform.deposit.service;

// In-process only (the DepositCreated outbox event is what other services see)
public record DepositCreatedEvent(Long depositId) {
}
