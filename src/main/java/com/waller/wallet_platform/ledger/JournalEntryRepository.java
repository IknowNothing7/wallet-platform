package com.waller.wallet_platform.ledger;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry,Long>{

    Optional<JournalEntry> findById(Long id);

    Page<JournalEntry> findAll(Pageable page);

    Page<JournalEntry> findAllByOrderByCreatedAtDescIdDesc(Pageable page);

    // Served by idx_journal_entry_reference
    List<JournalEntry> findByReferenceTypeAndReferenceIdOrderByIdAsc(String referenceType, Long referenceId);

}
