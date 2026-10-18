package com.waller.wallet_platform.hold.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import com.waller.wallet_platform.hold.dto.HoldDto;
import com.waller.wallet_platform.hold.entity.Hold;

@Mapper(componentModel = "spring")
public interface HoldMapper {

    @Mapping(target = "accountId", source = "account.id")
    @Mapping(target = "capturedEntryId", source = "capturedEntry.id")
    @Mapping(target = "merchantId", source = "merchant.id")
    HoldDto toDto(Hold hold);

}
