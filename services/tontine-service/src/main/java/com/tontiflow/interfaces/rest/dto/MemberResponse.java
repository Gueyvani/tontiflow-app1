package com.tontiflow.interfaces.rest.dto;

import com.tontiflow.domain.model.TontineMember;

public record MemberResponse(
        Long id,
        Long tontineId,
        Long userId,
        int sequentialOrder,
        boolean active
) {
    public static MemberResponse from(TontineMember member) {
        return new MemberResponse(
                member.getId(), member.getTontineId(), member.getUserId(),
                member.getSequentialOrder(), member.isActive());
    }
}
