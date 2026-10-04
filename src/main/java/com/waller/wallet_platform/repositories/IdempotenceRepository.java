package com.waller.wallet_platform.repositories;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.waller.wallet_platform.model.entites.IdempotencyKey;

@Repository 
public interface IdempotenceRepository extends JpaRepository<IdempotencyKey,Long> {

    Optional<IdempotencyKey> findById(Long id);

    Page<IdempotencyKey> findAll(Pageable page);

}
