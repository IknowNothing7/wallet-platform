package com.waller.wallet_platform.repositories;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.waller.wallet_platform.model.entites.Deposit;
import com.waller.wallet_platform.model.enums.DepositStatus;

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

}
