package com.waller.bank_sim.payment.repository;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import com.waller.bank_sim.payment.model.Payment;

@Component
public class PaymentStore {

    private final Map<String, Payment> byReference = new ConcurrentHashMap<>();
    private final Map<String, Payment> byIdempotencyKey = new ConcurrentHashMap<>();

    /** Atomically returns the payment already stored for the key, or stores the one the factory creates. */
    public Payment getOrCreate(String idempotencyKey, Function<String, Payment> factory) {
        return byIdempotencyKey.computeIfAbsent(idempotencyKey, key -> {
            Payment payment = factory.apply(key);
            byReference.put(payment.getReference(), payment);
            return payment;
        });
    }

    public Optional<Payment> findByReference(String reference) {
        return Optional.ofNullable(byReference.get(reference));
    }

    public Collection<Payment> findAll() {
        return byReference.values();
    }

    public void clear() {
        byIdempotencyKey.clear();
        byReference.clear();
    }
}
