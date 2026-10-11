package com.waller.notification_service.event.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "account_owner")
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class AccountOwner {

    @Id
    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "user_id", nullable = false)
    private Long userId;
}
