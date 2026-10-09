package com.waller.wallet_platform.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.waller.wallet_platform.model.dto.OutboxEventDto;
import com.waller.wallet_platform.model.entites.OutboxEvent;

@Mapper(componentModel = "spring")
public interface OutboxEventMapper {

    @Mapping(target = "payload", source = "payload")
    OutboxEventDto toDto(OutboxEvent event);

    // The entity holds a Jackson 2 JsonNode while MVC serializes with Jackson 3, so pass it on as JSON text
    default String payloadToJson(JsonNode payload) {
        return payload == null ? null : payload.toString();
    }

}
