package com.waller.wallet_platform.outbox;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import com.fasterxml.jackson.databind.JsonNode;
import org.mapstruct.Named;

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
