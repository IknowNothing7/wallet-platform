package com.waller.wallet_platform.common.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.waller.wallet_platform.common.util.ApiUtils;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;

// Remembers the response to a request sent with an Idempotency-Key header, so a retry gets the same answer
// instead of doing the work twice. Both methods join the caller's transaction: the key row is committed
// together with whatever the request created, or not at all.
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final Duration TTL = Duration.ofHours(24);
    private static final int MAX_KEY_LENGTH = 255;

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private final IdempotenceRepository idempotenceRepository;
    private final AppUserRepository appUserRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> Optional<T> findResponse(String key, String endpoint, Object request, Class<T> responseType) {
        if (key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be 1 to " + MAX_KEY_LENGTH + " characters");
        }
        Optional<IdempotencyKey> existing = idempotenceRepository.findByUserIdAndKey(currentUser().getId(), key);
        if (existing.isEmpty()) {
            return Optional.empty();
        }

        IdempotencyKey stored = existing.get();
        if (stored.getExpiresAt().isBefore(Instant.now())) {
            idempotenceRepository.delete(stored);
            idempotenceRepository.flush();
            return Optional.empty();
        }
        if (!stored.getEndpoint().equals(endpoint) || !stored.getRequestHash().equals(hash(request))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Idempotency-Key was already used for a different request");
        }
        return Optional.of(MAPPER.convertValue(stored.getResponseBody(), responseType));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(String key, String endpoint, Object request, HttpStatus status, Object response) {
        IdempotencyKey idempotencyKey = new IdempotencyKey();
        idempotencyKey.setUser(currentUser());
        idempotencyKey.setKey(key);
        idempotencyKey.setEndpoint(endpoint);
        idempotencyKey.setRequestHash(hash(request));
        idempotencyKey.setResponseStatus(status.value());
        idempotencyKey.setResponseBody(MAPPER.valueToTree(response));
        idempotencyKey.setExpiresAt(Instant.now().plus(TTL));
        try {
            idempotenceRepository.saveAndFlush(idempotencyKey);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A request with this Idempotency-Key is already being processed");
        }
    }

    private AppUser currentUser() {
        return appUserRepository.findByEmailIgnoreCase(ApiUtils.getCurrentUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private static String hash(Object request) {
        try {
            byte[] json = MAPPER.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Can not hash request", e);
        }
    }
}
