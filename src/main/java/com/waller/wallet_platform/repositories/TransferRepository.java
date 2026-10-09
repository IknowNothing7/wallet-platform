package com.waller.wallet_platform.repositories;

import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.waller.wallet_platform.model.entites.Transfer;

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

}
