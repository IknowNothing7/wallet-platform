package com.waller.wallet_platform.hold.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.waller.wallet_platform.hold.entity.Hold;
import com.waller.wallet_platform.hold.entity.HoldStatus;
import jakarta.persistence.LockModeType;

@Repository
public interface HoldRepository  extends JpaRepository<Hold,Long>
{
    Optional<Hold> findById(Long id);

    Page<Hold> findAll(Pageable pageable);

    // The account and its owner are fetched for the access check
    @EntityGraph(attributePaths = "account.user")
    @Query("select h from Hold h where h.id = :id")
    Optional<Hold> findWithAccountById(@Param("id") Long id);

    // Row lock so a capture, release and expiry of the same hold can't interleave
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Hold h where h.id = :id")
    Optional<Hold> findByIdForUpdate(@Param("id") Long id);

    // Holds in this status (ACTIVE) past expiresAt, oldest expiry first, for HoldExpiryJob; served by idx_hold_expiry.
    // The status is a parameter because Hibernate renders an enum literal as 'ACTIVE'::HoldStatus, a type that
    // doesn't exist (the column's type is hold_status).
    @Query("""
           select h.id from Hold h
           where h.status = :status and h.expiresAt <= :now
           order by h.expiresAt asc, h.id asc
           """)
    List<Long> findExpiredIds(@Param("status") HoldStatus status, @Param("now") Instant now, Pageable pageable);

    Page<Hold> findByAccountIdOrderByCreatedAtDescIdDesc(Long accountId, Pageable pageable);

    // Total still reserved on the account; holds past expiresAt no longer count
    @Query("""
           select coalesce(sum(h.amount), 0) from Hold h
           where h.account.id = :accountId and h.status = :status and h.expiresAt > :now
           """)
    long sumAmountByAccountIdAndStatus(@Param("accountId") Long accountId,
                                       @Param("status") HoldStatus status,
                                       @Param("now") Instant now);
}
