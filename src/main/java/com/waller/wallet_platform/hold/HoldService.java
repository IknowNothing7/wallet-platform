package com.waller.wallet_platform.hold;

import com.waller.wallet_platform.common.web.PageResponse;

public interface HoldService {

    HoldDto createHold(HoldRequest request);

    HoldDto getHold(Long id);

    PageResponse<HoldDto> getAccountHolds(Long accountId, int page, int size);

}
