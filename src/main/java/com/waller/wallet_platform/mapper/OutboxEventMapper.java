package com.waller.wallet_platform.mapper;

import com.waller.wallet_platform.model.request.OutboxEventRequest;
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

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "publishedAt", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "attempts", ignore = true)
    @Mapping(target = "lastError", ignore = true)
    @Mapping(
            target = "payload",
            source = "payload",
            qualifiedByName = "payloadToJson"
    )
    OutboxEvent requestToEntity(OutboxEventRequest request);

    @Named("payloadToJson")
    default String payloadToJson(JsonNode payload) {
        return payload == null ? null : payload.toString();
    }
}
