package com.waller.wallet_platform.model.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

import com.fasterxml.jackson.databind.JsonNode;

@AllArgsConstructor
@Getter
@Setter
@Builder
public class OutboxEventRequest  implements Serializable {

    @NotNull(message = "aggregateType can not be null")
    private String aggregateType;

    @NotNull(message = "aggregateId  can not be null")
    private Long aggregateId;

    @NotNull(message = "eventType can not be null")
    private String eventType;

    @NotNull(message = "payload can not be null")
    private String payload;
}
