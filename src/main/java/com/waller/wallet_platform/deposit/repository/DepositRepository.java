package com.waller.wallet_platform.deposit.repository;

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
import com.waller.wallet_platform.deposit.entity.Deposit;
import com.waller.wallet_platform.deposit.entity.DepositStatus;
import jakarta.persistence.LockModeType;

@Repository
public interface DepositRepository extends JpaRepository<Deposit,Long>{

Optional<Deposit> findById(Long id);

Page<Deposit> findAll(Pageable page);

Optional<Deposit> findByStatus(DepositStatus status);

// The account and its owner are fetched for the access check
@EntityGraph(attributePaths = "account.user")
@Query("select d from Deposit d where d.id = :id")
Optional<Deposit> findWithAccountById(@Param("id") Long id);

// Newest first; served by idx_deposit_account
Page<Deposit> findByAccountIdOrderByCreatedAtDescIdDesc(Long accountId, Pageable page);

// Serializes settlement: webhook, duplicate webhook and reconciliation can race on one deposit
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select d from Deposit d where d.id = :id")
Optional<Deposit> findByIdForUpdate(@Param("id") Long id);

// Oldest first, so a backlog is worked off in order
// TODO: idx_deposit_pending is on (status, expires_at); add one on (status, updated_at) if this gets slow
@Query("select d from Deposit d where d.status = :status and d.updatedAt < :before order by d.updatedAt asc")
List<Deposit> findStale(@Param("status") DepositStatus status, @Param("before") Instant before, Pageable page);

}
