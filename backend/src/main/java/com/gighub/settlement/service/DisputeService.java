package com.gighub.settlement.service;

import com.gighub.common.api.PageResponse;
import com.gighub.member.domain.UserRole;
import com.gighub.settlement.dto.DisputeListItemResponse;
import com.gighub.settlement.service.command.DisputeCreateCommand;

public interface DisputeService {

    Long create(DisputeCreateCommand command);

    PageResponse<DisputeListItemResponse> findPage(
            long workCaseId,
            long requesterUserId,
            UserRole requesterRole,
            int page,
            int size);
}
