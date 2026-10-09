package com.waller.wallet_platform.service;

import com.waller.wallet_platform.model.dto.HoldDto;
import com.waller.wallet_platform.model.response.PageResponse;

public interface HoldService {

    HoldDto getHold(Long id);

    PageResponse<HoldDto> getAccountHolds(Long accountId, int page, int size);

}
