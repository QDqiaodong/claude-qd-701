package com.aircraft.mro.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 模块六（出厂放行审签）入参。 */
public final class ReleaseDtos {

    private ReleaseDtos() {
    }

    /** 发起放行（生成草稿与第 1 轮证据快照） */
    @Data
    public static class CreateReq {

        private Long aircraftId;

        @NotBlank(message = "发起人不能为空")
        private String initiator;
    }

    /** 逐条保留依据 */
    @Data
    public static class RetainBasisItem {

        private Long defectId;

        @NotBlank(message = "每条开放故障都必须写明保留依据")
        private String retainBasis;
    }

    /** 提交审签：草稿 / 被驳回后再次提交都必须形成新的证据快照 */
    @Data
    public static class SubmitReq {

        @NotBlank(message = "提交人不能为空")
        private String submitter;

        /** 开放故障逐条保留依据（key=缺陷单 id）；有开放故障时缺一条都不行 */
        private List<RetainBasisItem> retainBasisList;
    }

    /** 机械放行签字 */
    @Data
    public static class MechSignReq {

        @NotBlank(message = "机械放行人不能为空")
        private String signer;
    }

    /** 质量终审：逐条确认开放故障保留依据 */
    @Data
    public static class QaApproveReq {

        @NotBlank(message = "质量终审人不能为空")
        private String signer;

        /** 终审逐条确认的缺陷单 id；必须覆盖快照中的全部开放故障 */
        private List<Long> confirmedDefectIds;
    }

    /** 驳回（必须填写原因，回到可修订状态） */
    @Data
    public static class RejectReq {

        @NotBlank(message = "驳回人不能为空")
        private String signer;

        @NotBlank(message = "驳回必须留下原因")
        private String reason;
    }
}
