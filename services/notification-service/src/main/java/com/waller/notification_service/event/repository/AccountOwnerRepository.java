package com.waller.notification_service.event.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.waller.notification_service.event.entity.AccountOwner;

public interface AccountOwnerRepository extends JpaRepository<AccountOwner, Long> {
}
