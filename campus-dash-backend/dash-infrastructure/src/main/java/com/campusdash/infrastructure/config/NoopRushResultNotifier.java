package com.campusdash.infrastructure.config;

import com.campusdash.domain.campaign.model.RushRequest;
import com.campusdash.domain.campaign.ports.RushResultNotifier;
import org.springframework.stereotype.Component;

/** 第一版以结果查询轮询为兜底；跨进程实时结果Topic后续可替换此端口。 */
@Component
public class NoopRushResultNotifier implements RushResultNotifier {
    @Override
    public void notifyResult(RushRequest request) {
        // 最终事实已经写入 rush_request，通知失败不影响业务结果。
    }
}
