package com.campusdash.presentation;

import com.campusdash.application.usecase.SubmitRushOrderUseCase;
import com.campusdash.application.usecase.query.QueryRushCampaignUseCase;
import com.campusdash.application.usecase.query.QueryRushRequestUseCase;
import com.campusdash.presentation.auth.CurrentUser;
import com.campusdash.shared.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class RushCampaignController {
    private final QueryRushCampaignUseCase campaigns;
    private final QueryRushRequestUseCase requests;
    private final SubmitRushOrderUseCase submit;

    public RushCampaignController(QueryRushCampaignUseCase campaigns,
                                  QueryRushRequestUseCase requests,
                                  SubmitRushOrderUseCase submit) {
        this.campaigns = campaigns;
        this.requests = requests;
        this.submit = submit;
    }

    @GetMapping("/api/rush-campaigns")
    public Result<List<QueryRushCampaignUseCase.View>> list(@RequestParam long campusId) {
        return Result.ok(campaigns.list(campusId));
    }

    @GetMapping("/api/rush-campaigns/{campaignId}")
    public Result<QueryRushCampaignUseCase.View> detail(@PathVariable long campaignId) {
        return Result.ok(campaigns.detail(campaignId));
    }

    @PostMapping("/api/rush-campaigns/{campaignId}/orders")
    public ResponseEntity<Result<SubmitRushOrderUseCase.Accepted>> submit(
            @PathVariable long campaignId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestBody SubmitBody body) {
        var accepted = submit.submit(new SubmitRushOrderUseCase.Command(
                campaignId, CurrentUser.get(), requestId, body.title(), body.rewardCents()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Result.ok(accepted));
    }

    @GetMapping("/api/rush-requests/{requestId}")
    public Result<QueryRushRequestUseCase.View> result(@PathVariable String requestId) {
        return Result.ok(requests.query(requestId, CurrentUser.get()));
    }

    public record SubmitBody(String title, long rewardCents) {}
}
