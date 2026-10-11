package com.waller.wallet_platform.outbox.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import com.fasterxml.jackson.databind.JsonNode;
import org.mapstruct.Named;
import com.waller.wallet_platform.outbox.dto.OutboxEventDto;
import com.waller.wallet_platform.outbox.entity.OutboxEvent;

@Mapper(componentModel = "spring")
public interface OutboxEventMapper {

    @Mapping(
            target = "payload",
            source = "payload",
            qualifiedByName = "payloadToJson"
    )
    OutboxEventDto toDto(OutboxEvent event);

    @Named("payloadToJson")
    default String payloadToJson(JsonNode payload) {
        return payload == null ? null : payload.toString();
    }

}
