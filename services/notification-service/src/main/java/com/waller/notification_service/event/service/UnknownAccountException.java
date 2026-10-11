package com.waller.notification_service.event.service;

/**
 * The event names an account whose AccountCreated has not been consumed yet (topics are not ordered
 * relative to each other). Thrown so the error handler retries the record later.
 */
public class UnknownAccountException extends RuntimeException {

    public UnknownAccountException(long accountId) {
        super("No owner known for account " + accountId);
    }
}
