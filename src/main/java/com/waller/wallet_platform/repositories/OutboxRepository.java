package com.waller.wallet_platform.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.waller.wallet_platform.model.entites.OutboxEvent;
import com.waller.wallet_platform.model.enums.OutboxStatus;

@Repository
public interface OutboxRepository  extends JpaRepository<OutboxEvent,Long>{

    Optional<OutboxEvent> findById(Long id);

    Page<OutboxEvent> findAll(Pageable page);

    Page<OutboxEvent> findAllByOrderByCreatedAtDescIdDesc(Pageable page);

    Page<OutboxEvent> findByStatusOrderByCreatedAtDescIdDesc(OutboxStatus status, Pageable page);

    // Oldest PENDING events, row-locked for the calling transaction; rows locked by another instance are skipped.
    // Served by idx_outbox_pending.
    @Query(value = "select * from outbox_event where status = 'PENDING' order by created_at, id limit :limit for update skip locked",
            nativeQuery = true)
    List<OutboxEvent> lockPendingBatch(@Param("limit") int limit);

    // Only FAILED events are reset, so a concurrent retry or publish can't be overwritten; returns 0 otherwise.
    // lastError is kept so the previous failure stays visible until the next attempt.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
   @Query(value = "update outbox_event set status = 'PENDING', attempts = 0 where id = :id and status = 'FAILED'",
            nativeQuery = true)
    int resetFailedToPending(@Param("id") Long id);

}
