package com.waller.wallet_platform.transfer.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.waller.wallet_platform.transfer.entity.TransferLimit;

@Repository
public interface TransferLimitRepository extends JpaRepository<TransferLimit, Long> {

    Optional<TransferLimit> findByAccountId(Long accountId);

    Optional<TransferLimit> findByCurrency(String currency);
}
