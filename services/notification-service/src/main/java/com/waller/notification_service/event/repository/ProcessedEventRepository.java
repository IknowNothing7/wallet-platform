package com.waller.notification_service.event.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.waller.notification_service.event.entity.ProcessedEvent;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {
}
