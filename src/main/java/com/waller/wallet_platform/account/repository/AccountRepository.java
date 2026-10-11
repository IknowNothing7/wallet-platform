package com.waller.wallet_platform.account.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.entity.SystemAccountKind;
import jakarta.persistence.LockModeType;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findById(Long id);

    // Row lock so concurrent balance checks on the same account run one after another
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);

    // User-facing lookups: system accounts are invisible here, so they answer like a missing id
    @Query("select a from Account a where a.id = :id and a.systemKind is null")
    Optional<Account> findUserAccountById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id and a.systemKind is null")
    Optional<Account> findUserAccountByIdForUpdate(@Param("id") Long id);

    Optional<Account> findBySystemKindAndCurrency(SystemAccountKind systemKind, String currency);

    // Racing creators both succeed: the loser's insert hits uq_account_system_kind_currency and is skipped
    @Modifying
    @Query(value = """
            INSERT INTO account (currency, type, system_kind)
            VALUES (:currency, 'SYSTEM', CAST(:kind AS system_account_kind))
            ON CONFLICT DO NOTHING""", nativeQuery = true)
    int insertSystemAccountIfAbsent(@Param("kind") String kind, @Param("currency") String currency);

    Page<Account> findAll(Pageable page);

    Optional<Account> findByUser(Long id);

    List<Account> findByUserEmailIgnoreCaseOrderByIdAsc(String email);
}
