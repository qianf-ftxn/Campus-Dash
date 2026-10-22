package com.campusdash.presentation;

import com.campusdash.application.usecase.*;
import com.campusdash.application.usecase.query.ErrandActionResolver;
import com.campusdash.application.usecase.query.GetErrandDetailUseCase;
import com.campusdash.application.usecase.query.QueryErrandListUseCase;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.presentation.auth.CurrentUser;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.Result;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务接口：发布 / 列表 / 详情 / 全部生命周期操作。
 *
 * 身份不再从 X-User-Id 裸取，而是从 AuthInterceptor 解析出的 CurrentUser 拿。
 * 前端渲染的操作按钮由详情接口的 availableActions 驱动（见 ErrandActionResolver），
 * 前端不实现第二份状态机。
 */
@RestController
@RequestMapping("/api/errands")
public class ErrandController {

    private final PublishErrandUseCase publishUseCase;
    private final GrabErrandUseCase grabUseCase;
    private final ConfirmErrandUseCase confirmUseCase;
    private final PickUpErrandUseCase pickUpUseCase;
    private final DeliverErrandUseCase deliverUseCase;
    private final SettleErrandUseCase settleUseCase;
    private final RefundErrandUseCase refundUseCase;
    private final DisputeErrandUseCase disputeUseCase;
    private final ArbitrateErrandUseCase arbitrateUseCase;
    private final QueryErrandListUseCase queryUseCase;
    private final GetErrandDetailUseCase detailUseCase;
    private final ErrandActionResolver actionResolver;
    private final ErrandRepository errandRepository;
    private final com.campusdash.application.usecase.query.WorkspaceRolePolicy workspaceRoles;

    public ErrandController(PublishErrandUseCase publishUseCase,
                            GrabErrandUseCase grabUseCase,
                            ConfirmErrandUseCase confirmUseCase,
                            PickUpErrandUseCase pickUpUseCase,
                            DeliverErrandUseCase deliverUseCase,
                            SettleErrandUseCase settleUseCase,
                            RefundErrandUseCase refundUseCase,
                            DisputeErrandUseCase disputeUseCase,
                            ArbitrateErrandUseCase arbitrateUseCase,
                            QueryErrandListUseCase queryUseCase,
                            GetErrandDetailUseCase detailUseCase,
                            ErrandActionResolver actionResolver,
                            ErrandRepository errandRepository,
                            com.campusdash.application.usecase.query.WorkspaceRolePolicy workspaceRoles) {
        this.publishUseCase = publishUseCase;
        this.grabUseCase = grabUseCase;
        this.confirmUseCase = confirmUseCase;
        this.pickUpUseCase = pickUpUseCase;
        this.deliverUseCase = deliverUseCase;
        this.settleUseCase = settleUseCase;
        this.refundUseCase = refundUseCase;
        this.disputeUseCase = disputeUseCase;
        this.arbitrateUseCase = arbitrateUseCase;
        this.queryUseCase = queryUseCase;
        this.detailUseCase = detailUseCase;
        this.actionResolver = actionResolver;
        this.errandRepository = errandRepository;
        this.workspaceRoles = workspaceRoles;
    }

    public record PublishRequest(Long campusId, ErrandType type, String title,
                                 Long rewardCents, Integer slotTotal) {}

    @PostMapping
    public Result<PublishErrandUseCase.Result> publish(@RequestBody PublishRequest req) {
        long userId = CurrentUser.get();
        if (req.rewardCents() == null) return Result.fail(ErrorCode.INVALID_ARGUMENT);
        var cmd = new PublishErrandUseCase.Command(
                req.campusId() == null ? 1L : req.campusId(),
                userId,
                req.type() == null ? ErrandType.DELIVERY : req.type(),
                req.title(),
                req.rewardCents(),
                req.slotTotal() == null ? 1 : req.slotTotal());
        return Result.ok(publishUseCase.publish(cmd));
    }

    /** 任务广场：默认只看 PUBLISHED */
    @GetMapping
    public Result<Object> list(@RequestParam(defaultValue = "1") long campusId,
                               @RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size,
                               @RequestParam(required = false) String cursor,
                               @RequestParam(required = false) String type,
                               @RequestParam(required = false) String keyword,
                               @RequestParam(defaultValue = "latest") String sort) {
        long viewer = CurrentUser.get();
        if (cursor != null) {
            if (type != null || keyword != null || !"latest".equals(sort)) return Result.fail(ErrorCode.INVALID_ARGUMENT);
            var cursorPage = queryUseCase.listByCursor(campusId, status, cursor, size);
            return Result.ok(Map.of(
                    "items", toCards(cursorPage.items(), viewer),
                    "nextCursor", cursorPage.nextCursor() == null ? "" : cursorPage.nextCursor()));
        }
        return Result.ok(toCards(queryUseCase.search(campusId, status, type, keyword, sort, page, size), viewer));
    }

    /** 我的任务：我发布的 / 我抢的 */
    @GetMapping("/mine")
    public Result<List<Map<String, Object>>> mine(@RequestParam(defaultValue = "PUBLISHED_BY_ME") String role,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  @RequestParam(defaultValue = "latest") String sort) {
        long userId = CurrentUser.get();
        List<Errand> list = queryUseCase.personalOrders(userId, "GRABBED_BY_ME".equals(role), sort, page, size);
        return Result.ok(toCards(list, userId));
    }

    /**
     * 详情：带 availableActions，前端据此渲染按钮。
     *
     * 走缓存（P5）：任务的静态与状态字段来自 Cache Aside 链路；
     * availableActions 与 role 不进缓存——它们依赖"谁在看"，
     * 缓存进去会导致 A 用户看到 B 用户的按钮集合（缓存串号）。
     * 这是缓存设计里最容易出事的一类：**凡是与请求者身份相关的派生数据都不能进共享缓存**。
     */
    @GetMapping("/{errandId}")
    public Result<Map<String, Object>> detail(@PathVariable long errandId) {
        long viewer = CurrentUser.get();
        var cachedJson = detailUseCase.detailJson(errandId);
        if (cachedJson.isEmpty()) {
            return Result.fail(ErrorCode.ERRAND_NOT_FOUND);
        }
        // 动作集合按查看者实时计算：需要聚合本体做身份判定，这一步不走缓存
        return errandRepository.findById(errandId)
                .map(e -> { requireVisible(e, viewer); return Result.ok(toCard(e, viewer)); })
                .orElseGet(() -> Result.fail(ErrorCode.ERRAND_NOT_FOUND));
    }

    /** 状态时间线（事件溯源的展示面） */
    @GetMapping("/{errandId}/timeline")
    public Result<List<Map<String, Object>>> timeline(@PathVariable long errandId) {
        long viewer = CurrentUser.get();
        var errand = errandRepository.findById(errandId)
                .orElseThrow(() -> new com.campusdash.shared.BizException(ErrorCode.ERRAND_NOT_FOUND));
        requireVisible(errand, viewer);
        return Result.ok(queryUseCase.statusLog(errandId).stream()
                .map(c -> Map.<String, Object>of(
                        "time", c.time().toString(), "from", c.from(), "to", c.to(),
                        "round", c.round(), "operatorId", c.operatorId()))
                .toList());
    }

    /**
     * 抢单。requestId 由客户端传入做幂等，重试用同一个值。
     * 并发抢单演示时前端会同时发 N 个请求、各自带不同 requestId。
     */
    @PostMapping("/{errandId}/grab")
    public Result<GrabErrandUseCase.Result> grab(@PathVariable long errandId,
                                                 @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        long runnerId = CurrentUser.get();
        var cmd = new GrabErrandUseCase.Command(
                errandId, runnerId,
                requestId == null ? UUID.randomUUID().toString() : requestId,
                60);
        GrabErrandUseCase.Result result = grabUseCase.grab(cmd);
        return result.grabbed() ? Result.ok(result) : Result.fail(result.code(), result);
    }

    @PostMapping("/{errandId}/confirm")
    public Result<Void> confirm(@PathVariable long errandId) {
        confirmUseCase.confirm(new ConfirmErrandUseCase.Command(errandId, CurrentUser.get()));
        return Result.ok(null);
    }

    @PostMapping("/{errandId}/pickup")
    public Result<Void> pickup(@PathVariable long errandId) {
        pickUpUseCase.pickUp(errandId, CurrentUser.get());
        return Result.ok(null);
    }

    @PostMapping("/{errandId}/deliver")
    public Result<Void> deliver(@PathVariable long errandId) {
        deliverUseCase.deliver(errandId, CurrentUser.get());
        return Result.ok(null);
    }

    @PostMapping("/{errandId}/settle")
    public Result<Map<String, Object>> settle(@PathVariable long errandId) {
        var r = settleUseCase.settle(errandId, CurrentUser.get());
        return r.name().equals("CONFLICT") ? Result.fail(ErrorCode.SETTLE_CONFLICT)
                : Result.ok(Map.of("result", r.name()));
    }

    @PostMapping("/{errandId}/cancel")
    public Result<Map<String, Object>> cancel(@PathVariable long errandId) {
        var r = refundUseCase.cancelAndRefund(errandId, CurrentUser.get());
        return r.name().equals("CONFLICT") ? Result.fail(ErrorCode.REFUND_CONFLICT)
                : Result.ok(Map.of("result", r.name()));
    }

    @PostMapping("/{errandId}/dispute")
    public Result<Void> dispute(@PathVariable long errandId) {
        disputeUseCase.raise(errandId, CurrentUser.get());
        return Result.ok(null);
    }

    public record ArbitrateRequest(String favor) {}

    @PostMapping("/{errandId}/arbitrate")
    public Result<Map<String, Object>> arbitrate(@PathVariable long errandId,
                                                 @RequestBody ArbitrateRequest req) {
        if (!"PUBLISHER".equals(req.favor()) && !"RUNNER".equals(req.favor()))
            return Result.fail(ErrorCode.INVALID_ARGUMENT);
        var favor = ArbitrateErrandUseCase.Favor.valueOf(req.favor());
        var r = arbitrateUseCase.arbitrate(errandId, favor, CurrentUser.get());
        return r == ArbitrateErrandUseCase.Result.CONFLICT ? Result.fail(ErrorCode.SETTLE_CONFLICT)
                : Result.ok(Map.of("result", r.name()));
    }

    private void requireVisible(Errand e, long viewer) {
        var role = workspaceRoles.roleFor(viewer);
        boolean allowed = role == com.campusdash.application.usecase.query.WorkspaceRolePolicy.Role.ARBITRATOR
                || role == com.campusdash.application.usecase.query.WorkspaceRolePolicy.Role.PUBLISHER && e.publisherId() == viewer
                || role == com.campusdash.application.usecase.query.WorkspaceRolePolicy.Role.RUNNER
                   && (e.status().grabbable() || e.grabberId() != null && e.grabberId() == viewer);
        if (!allowed) throw new com.campusdash.shared.BizException(ErrorCode.FORBIDDEN);
    }

    /** 统一的任务卡片：列表与详情共用，保证 availableActions 处处一致 */
    private List<Map<String, Object>> toCards(List<Errand> errands, long viewer) {
        var times = queryUseCase.creationTimes(errands);
        return errands.stream().map(e -> toCard(e, viewer, times.get(e.id()))).toList();
    }

    private Map<String, Object> toCard(Errand e, long viewer) {
        return toCard(e, viewer, queryUseCase.creationTimes(List.of(e)).get(e.id()));
    }

    private Map<String, Object> toCard(Errand e, long viewer, java.time.Instant createdAt) {
        var resolved = actionResolver.resolve(e, viewer);
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", e.id());
        card.put("title", e.title());
        card.put("createdAt", createdAt == null ? null : createdAt.toString());
        card.put("status", e.status().name());
        card.put("type", e.type().name());
        card.put("rewardCents", e.reward().cents());
        card.put("slotTotal", e.slotTotal());
        card.put("slotTaken", e.slotTaken());
        card.put("publisherId", e.publisherId());
        card.put("grabberId", e.grabberId() == null ? -1L : e.grabberId());
        card.put("round", e.round());
        card.put("role", resolved.role());
        card.put("availableActions", resolved.actions());
        return card;
    }
}
