package com.waller.wallet_platform.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.waller.wallet_platform.model.entites.Posting;

@Repository
public interface PostingRepository extends JpaRepository<Posting,Long>{

    Optional<Posting> findById(Long id);

    Page<Posting> findAll(Pageable page);

    // Newest first; served by idx_posting_account_created
    Page<Posting> findByAccountIdOrderByCreatedAtDescIdDesc(Long accountId, Pageable page);

    List<Posting> findByJournalEntryIdOrderByIdAsc(Long journalEntryId);

}
