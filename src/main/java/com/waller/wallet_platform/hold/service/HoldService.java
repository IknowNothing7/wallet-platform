package com.waller.wallet_platform.hold.service;

import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.hold.dto.HoldCaptureRequest;
import com.waller.wallet_platform.hold.dto.HoldDto;
import com.waller.wallet_platform.hold.dto.HoldRequest;

public interface HoldService {

    HoldDto createHold(HoldRequest request);

    HoldDto getHold(Long id);

    PageResponse<HoldDto> getAccountHolds(Long accountId, int page, int size);

    // Each transition is idempotent: repeating it on a hold already in the target state returns it unchanged
    HoldDto captureHold(Long id, HoldCaptureRequest request);

    HoldDto releaseHold(Long id);

    HoldDto expireHold(Long id);

}
