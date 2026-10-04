package com.waller.wallet_platform.repositories;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.waller.wallet_platform.model.entites.OutboxEvent;

@Repository 
public interface OutboxRepository  extends JpaRepository<OutboxEvent,Long>{

    Optional<OutboxEvent> findById(Long id);

    Page<OutboxEvent> findAll(Pageable page);

}
