package com.campusdash.application.usecase.query;

import com.campusdash.domain.campaign.model.RushRequest;
import com.campusdash.domain.campaign.ports.RushRequestRepository;
import com.campusdash.domain.campaign.ports.RushReservationPort;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import org.springframework.stereotype.Service;

@Service
public class QueryRushRequestUseCase {
    private final RushRequestRepository requests;
    private final RushReservationPort reservations;

    public QueryRushRequestUseCase(RushRequestRepository requests, RushReservationPort reservations) {
        this.requests = requests;
        this.reservations = reservations;
    }

    public View query(String requestId, long currentUserId) {
        // 活动结果会被高频轮询，先读Redis才能让查询链路也承接住峰值；
        // 缓存丢失或过期时再回源MySQL，恢复job会修补成功结果缓存。
        RushRequest request = reservations.findCached(requestId)
                .or(() -> requests.findByRequestId(requestId))
                .orElseThrow(() -> new BizException(ErrorCode.RUSH_REQUEST_NOT_FOUND));
        if (request.userId() != currentUserId) {
            throw new BizException(ErrorCode.RUSH_REQUEST_FORBIDDEN);
        }
        return new View(request.requestId(), request.status().publicStatus(), request.errandId(),
                request.errorCode() == null ? "" : request.errorCode());
    }

    public record View(String requestId, String status, Long errandId, String errorCode) {}
}
