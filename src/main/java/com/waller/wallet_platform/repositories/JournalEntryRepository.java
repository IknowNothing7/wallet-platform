package com.waller.wallet_platform.repositories;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.waller.wallet_platform.model.entites.JournalEntry;

@Repository 
public interface JournalEntryRepository extends JpaRepository<JournalEntry,Long>{

    Optional<JournalEntry> findById(Long id);

    Page<JournalEntry> findAll(Pageable page);

}
