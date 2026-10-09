package com.waller.wallet_platform.repositories;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.waller.wallet_platform.model.entites.Hold;

@Repository
public interface HoldRepository  extends JpaRepository<Hold,Long>
{
    Optional<Hold> findById(Long id);

    Page<Hold> findAll(Pageable pageable);

    // The account and its owner are fetched for the access check
    @EntityGraph(attributePaths = "account.user")
    @Query("select h from Hold h where h.id = :id")
    Optional<Hold> findWithAccountById(@Param("id") Long id);

    Page<Hold> findByAccountIdOrderByCreatedAtDescIdDesc(Long accountId, Pageable pageable);
}
