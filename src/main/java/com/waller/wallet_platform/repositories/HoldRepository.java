package com.waller.wallet_platform.repositories;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.waller.wallet_platform.model.entites.Hold;

@Repository 
public interface HoldRepository  extends JpaRepository<Hold,Long>
{
    Optional<Hold> findById(Long id);

    Page<Hold> findAll(Pageable pageable);
}
