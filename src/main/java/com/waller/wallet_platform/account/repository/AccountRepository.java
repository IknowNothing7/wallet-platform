package com.waller.wallet_platform.account.repository;

import java.util.Collection;
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
import com.waller.wallet_platform.account.entity.AccountType;
import com.waller.wallet_platform.account.entity.SystemAccountKind;
import jakarta.persistence.LockModeType;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findById(Long id);

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

    // The ledger's lock (LedgerServiceImpl): every account of an entry in one statement, user accounts before system
    // accounts and each by id. Postgres locks the rows in the ORDER BY order, so all writers queue the same way. The
    // type is a parameter because Hibernate would render an enum literal with a type name that doesn't exist.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           select a from Account a where a.id in :ids
           order by case when a.type = :systemType then 1 else 0 end, a.id
           """)
    List<Account> findAllByIdForUpdateInLockOrder(@Param("ids") Collection<Long> ids,
                                                  @Param("systemType") AccountType systemType);

    Page<Account> findAll(Pageable page);

    List<Account> findByUserEmailIgnoreCaseOrderByIdAsc(String email);

    boolean existsByUserIdAndCurrency(Long userId, String currency);
}
