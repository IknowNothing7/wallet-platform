package com.waller.wallet_platform.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.waller.wallet_platform.model.dto.OutboxEventDto;
import com.waller.wallet_platform.model.entites.OutboxEvent;
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
