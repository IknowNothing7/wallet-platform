package com.waller.wallet_platform.repositories;

import java.util.Optional;

import org.springdoc.core.converters.models.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.waller.wallet_platform.model.entites.Transfer;

@Repository 
public interface TransferRepository extends JpaRepository<Transfer, Long>{

Optional<Transfer> findById(Long id);

Page<Transfer> findAll(Pageable page);

Optional<Transfer> findByFromAccount(Long id);

Optional<Transfer> findByToAccount(Long id);

Optional<String> findByCurrency(char cur);

}
