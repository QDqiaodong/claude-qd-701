package com.aircraft.mro.controller;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aircraft.mro.common.ApiResponse;
import com.aircraft.mro.common.BizException;
import com.aircraft.mro.dto.ReleaseDtos;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.service.ReleaseService;

import jakarta.validation.Valid;

/** 模块六：出厂放行审签。 */
@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    private final ReleaseService releaseService;

    public ReleaseController(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    /** 顶部总览计数：待审 / 已失效 / 已放行（全局 + 当前机号） */
    @GetMapping("/overview")
    public ApiResponse<Views.ReleaseOverview> overview(
            @RequestParam(value = "aircraftId", required = false) Long aircraftId) {
        return ApiResponse.success(releaseService.overview(aircraftId));
    }

    /** 当前飞机的发起门禁：适航/机位/未闭环工卡/超期故障，全部服务端算好 */
    @GetMapping("/gate")
    public ApiResponse<Views.AircraftGateView> gate(@RequestParam("aircraftId") Long aircraftId) {
        return ApiResponse.success(releaseService.gate(aircraftId));
    }

    /**
     * 出位核验（机位页与放行模块共用同一判定）：返回是否可出位 + 一致的阻断文案，
     * 以及所依据的放行单号。只读，不改状态；真正出位仍由 /api/bays/{id}/release 在锁内复核。
     */
    @GetMapping("/departure-check")
    public ApiResponse<java.util.Map<String, Object>> departureCheck(
            @RequestParam("aircraftId") Long aircraftId) {
        return ApiResponse.success(releaseService.departureCheck(aircraftId));
    }

    @GetMapping
    public ApiResponse<List<Views.ReleaseOrderView>> list(
            @RequestParam(value = "aircraftId", required = false) Long aircraftId) {
        return ApiResponse.success(releaseService.list(aircraftId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Views.ReleaseDetailView> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(releaseService.detail(id));
    }

    /** 某一轮证据快照与当前事实的差异（默认最新一轮） */
    @GetMapping("/{id}/diff")
    public ApiResponse<Views.SnapshotDiffView> diff(
            @PathVariable("id") Long id,
            @RequestParam(value = "roundNo", required = false) Integer roundNo) {
        return ApiResponse.success(releaseService.diff(id, roundNo));
    }

    /** 发起放行：生成草稿与第 1 轮不可变证据快照 */
    @PostMapping
    public ApiResponse<Views.ReleaseDetailView> create(@Valid @RequestBody ReleaseDtos.CreateReq req) {
        return ApiResponse.success("已发起出厂放行审签并生成证据快照", releaseService.create(req));
    }

    /** 提交审签（草稿 / 被驳回后再次提交都形成新快照） */
    @PostMapping("/{id}/submit")
    public ApiResponse<Views.ReleaseDetailView> submit(@PathVariable("id") Long id,
                                                       @Valid @RequestBody ReleaseDtos.SubmitReq req) {
        return ApiResponse.success("已提交，进入待机械放行", releaseService.submit(id, req));
    }

    /** 机械放行签字 */
    @PostMapping("/{id}/mech-sign")
    public ApiResponse<Views.ReleaseDetailView> mechSign(@PathVariable("id") Long id,
                                                         @Valid @RequestBody ReleaseDtos.MechSignReq req) {
        return ApiResponse.success("机械放行已签字", releaseService.mechSign(id, req));
    }

    /** 质量终审通过（第三人 + 逐条确认开放故障保留依据） */
    @PostMapping("/{id}/qa-approve")
    public ApiResponse<Views.ReleaseDetailView> qaApprove(@PathVariable("id") Long id,
                                                          @Valid @RequestBody ReleaseDtos.QaApproveReq req) {
        return ApiResponse.success("质量终审通过，飞机准许出厂出位", releaseService.qaApprove(id, req));
    }

    /** 驳回（必须留原因，回到可修订状态） */
    @PostMapping("/{id}/reject")
    public ApiResponse<Views.ReleaseDetailView> reject(@PathVariable("id") Long id,
                                                       @Valid @RequestBody ReleaseDtos.RejectReq req) {
        return ApiResponse.success("已驳回，发起人可修订后重新提交", releaseService.reject(id, req));
    }

    /**
     * 【验收演示】两人同时终审：并发发起两个终审请求。
     * 同一终审人重复点击 → 两者拿到同一结果（幂等）；两个不同终审人抢签 → 只成一个，
     * 另一个收到"当前已放行"的冲突告知。
     */
    @PostMapping("/{id}/concurrent-qa")
    public ApiResponse<List<Views.ConcurrentQaResult>> concurrentQa(
            @PathVariable("id") Long id,
            @RequestParam(value = "sameActor", required = false, defaultValue = "false") boolean sameActor,
            @RequestBody(required = false) ReleaseDtos.QaApproveReq req) {
        releaseService.requirePendingQaForDemo(id);
        String actorA = name(req == null ? null : req.getSigner(), "罗建军");
        String actorB = sameActor ? actorA : "周国平";
        List<Long> confirmed = req == null || req.getConfirmedDefectIds() == null
                ? List.of() : req.getConfirmedDefectIds();

        // 两个真正并发、各自独立事务的终审请求
        CompletableFuture<Views.ConcurrentQaResult> fa = CompletableFuture.supplyAsync(
                () -> runQa(id, actorA, confirmed, "A"));
        CompletableFuture<Views.ConcurrentQaResult> fb = CompletableFuture.supplyAsync(
                () -> runQa(id, actorB, confirmed, "B"));
        try {
            CompletableFuture.allOf(fa, fb).join();
            List<Views.ConcurrentQaResult> results = List.of(fa.get(), fb.get());
            long ok = results.stream().filter(Views.ConcurrentQaResult::success).count();
            String message = ok == 2 && sameActor
                    ? "同一终审人重复点击：两次请求返回同一放行结果（幂等）"
                    : "两个不同终审人同时终审：仅一人成功，另一人被明确告知当前状态（只可能有一个成功）";
            return ApiResponse.success(message, results);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("并发演示被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof BizException biz) {
                throw biz;
            }
            throw new BizException("并发演示异常：" + cause.getMessage());
        }
    }

    private Views.ConcurrentQaResult runQa(Long id, String actor, List<Long> confirmed, String tag) {
        ReleaseDtos.QaApproveReq body = new ReleaseDtos.QaApproveReq();
        body.setSigner(actor);
        body.setConfirmedDefectIds(confirmed);
        try {
            Views.ReleaseDetailView detail = releaseService.qaApprove(id, body);
            return new Views.ConcurrentQaResult(actor, true,
                    "请求" + tag + "（" + actor + "）终审成功，单据已放行",
                    detail.order().status(), detail.order().statusName(),
                    detail.order().qaSigner(), detail.order().qaSignedAt());
        } catch (BizException e) {
            // 竞争失败者：回读服务端当前状态，明确告知，而不是报 500
            Views.ReleaseDetailView now = releaseService.detail(id);
            return new Views.ConcurrentQaResult(actor, false,
                    "请求" + tag + "（" + actor + "）未成功：" + e.getMessage(),
                    now.order().status(), now.order().statusName(),
                    now.order().qaSigner(), now.order().qaSignedAt());
        }
    }

    private String name(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
