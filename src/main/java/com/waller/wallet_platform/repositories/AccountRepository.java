package com.waller.wallet_platform.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.waller.wallet_platform.model.entites.Account;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findById(Long id);

    Page<Account> findAll(Pageable page);

    // The current user's accounts; the principal's name is the user's email
    List<Account> findByUserEmailIgnoreCaseOrderByIdAsc(String email);
}
