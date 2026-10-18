package com.waller.wallet_platform.transfer.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.waller.wallet_platform.transfer.entity.Transfer;
import com.waller.wallet_platform.transfer.entity.TransferStatus;
import jakarta.persistence.LockModeType;

@Repository
public interface TransferRepository extends JpaRepository<Transfer, Long>{

Optional<Transfer> findById(Long id);

Page<Transfer> findAll(Pageable page);

// Both accounts and their owners are fetched for the access check
@EntityGraph(attributePaths = { "fromAccount.user", "toAccount.user" })
@Query("select t from Transfer t where t.id = :id")
Optional<Transfer> findWithAccountsById(@Param("id") Long id);

// Incoming and outgoing, newest first; served by idx_transfer_from / idx_transfer_to
@Query(value = "select t from Transfer t where t.fromAccount.id = :accountId or t.toAccount.id = :accountId order by t.createdAt desc, t.id desc",
        countQuery = "select count(t) from Transfer t where t.fromAccount.id = :accountId or t.toAccount.id = :accountId")
Page<Transfer> findByAccountId(@Param("accountId") Long accountId, Pageable page);

// Serializes processing and reversal of one transfer
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select t from Transfer t where t.id = :id")
Optional<Transfer> findByIdForUpdate(@Param("id") Long id);

// Oldest first, skipping transfers that are backing off after a failed attempt; served by idx_transfer_pending
@Query("""
       select t.id from Transfer t
       where t.status = :status and t.createdAt < :before and (t.nextAttemptAt is null or t.nextAttemptAt <= :now)
       order by t.createdAt asc, t.id asc
       """)
List<Long> findDueIds(@Param("status") TransferStatus status,
                      @Param("before") Instant before,
                      @Param("now") Instant now,
                      Pageable page);

// By createdAt, not updatedAt: every failed attempt touches updatedAt, which would hide a stuck transfer
long countByStatusAndCreatedAtBefore(TransferStatus status, Instant before);

// Null when nothing is stale
@Query("select min(t.createdAt) from Transfer t where t.status = :status and t.createdAt < :before")
Instant findOldestCreatedAt(@Param("status") TransferStatus status, @Param("before") Instant before);

// Outgoing total for the daily limit; served by idx_transfer_from
@Query("""
       select coalesce(sum(t.amount), 0) from Transfer t
       where t.fromAccount.id = :accountId and t.status = :status and t.createdAt > :since
       """)
long sumAmountByFromAccountIdAndStatusSince(@Param("accountId") Long accountId,
                                            @Param("status") TransferStatus status,
                                            @Param("since") Instant since);

}
