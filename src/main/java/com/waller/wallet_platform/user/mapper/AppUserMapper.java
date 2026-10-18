package com.waller.wallet_platform.user.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import com.waller.wallet_platform.auth.dto.RegistrationRequest;
import com.waller.wallet_platform.user.dto.AppUserDto;
import com.waller.wallet_platform.user.dto.UpdateUserRequest;
import com.waller.wallet_platform.user.dto.UserEvent;
import com.waller.wallet_platform.user.entity.AppUser;

@Mapper(componentModel = "spring",nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface AppUserMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "role", constant = "USER")
    @Mapping(target = "passwordHash", ignore = true)
    AppUser registrationToEntity(RegistrationRequest request);

    @Mapping(target = "name", source = "username")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "email", ignore = true)
    @Mapping(target = "role", ignore = true)
    @Mapping(target = "passwordHash", ignore = true)
    void updateEntity(UpdateUserRequest request, @MappingTarget AppUser user);

    AppUserDto toDto(AppUser user);

    UserEvent toEvent(AppUser user);

}
