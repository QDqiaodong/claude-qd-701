package com.aircraft.mro.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 机库 MRO 词典与业务规则参数：机型机位匹配、适航状态、工卡状态机、保留期限折算等。
 * 规则集中在此，改判据只改这一处。
 */
public final class MroDict {

    private MroDict() {
    }

    // ---------------- 机型与机位 ----------------
    /** 机型 → 机身全长（米），用于判断是否塞得进机位 */
    private static final Map<String, BigDecimal> MODEL_LENGTH = Map.of(
            "B737", BigDecimal.valueOf(39.5),
            "A320", BigDecimal.valueOf(37.6),
            "A330", BigDecimal.valueOf(63.7),
            "ARJ21", BigDecimal.valueOf(33.5));

    /** 机位尺寸等级 → 允许停放的机型 */
    private static final Map<String, List<String>> SIZE_FIT = Map.of(
            "A", List.of("B737", "A320", "A330", "ARJ21"),
            "B", List.of("B737", "A320", "ARJ21"),
            "C", List.of("ARJ21"));

    private static final Map<String, String> SIZE_CLASS_NAME = Map.of(
            "A", "A 类（大型机位）",
            "B", "B 类（中型机位）",
            "C", "C 类（支线机位）");

    private static final Map<String, String> BAY_STATUS = Map.of(
            "IDLE", "空闲",
            "OCCUPIED", "占用",
            "DISABLED", "停用");

    // ---------------- 适航状态 ----------------
    private static final Map<String, String> AIRWORTHY = Map.of(
            "AIRWORTHY", "适航",
            "GROUNDED", "停场待修",
            "INSPECTION", "定检中",
            "STORED", "封存");

    /** 封存机不允许开工卡 */
    public static final String AIRWORTHY_STORED = "STORED";
    /** 停场待修机不允许出位（排故未完成不得出厂） */
    public static final String AIRWORTHY_GROUNDED = "GROUNDED";

    // ---------------- 工卡 ----------------
    private static final Map<String, String> CHECK_TYPE = Map.of(
            "A_CHECK", "A检",
            "C_CHECK", "C检",
            "ROUTINE", "例行",
            "SPECIAL", "专项");

    /** 检类 → 计划工时上限（小时） */
    private static final Map<String, BigDecimal> CHECK_MAX_PLAN_HOUR = Map.of(
            "A_CHECK", BigDecimal.valueOf(24),
            "C_CHECK", BigDecimal.valueOf(120),
            "ROUTINE", BigDecimal.valueOf(16),
            "SPECIAL", BigDecimal.valueOf(48));

    private static final Map<String, String> CARD_STATUS = Map.of(
            "ASSIGNED", "待派工",
            "IN_PROGRESS", "进行中",
            "WAIT_INSP", "待检验",
            "CLOSED", "已关闭",
            "CANCELED", "已取消");

    /** 状态机：当前状态 → 允许的下一状态 */
    private static final Map<String, List<String>> CARD_NEXT = Map.of(
            "ASSIGNED", List.of("IN_PROGRESS", "CANCELED"),
            "IN_PROGRESS", List.of("WAIT_INSP", "CANCELED"),
            "WAIT_INSP", List.of("CLOSED"),
            "CLOSED", List.of(),
            "CANCELED", List.of());

    /** 单张工卡实际工时上限 */
    public static final BigDecimal MAX_ACTUAL_HOUR = BigDecimal.valueOf(200);

    // ---------------- 故障保留 ----------------
    private static final Map<String, String> LIMIT_UNIT = Map.of(
            "DAY", "飞行日",
            "HOUR", "飞行小时");

    private static final Map<String, String> DEFECT_STATUS = Map.of(
            "OPEN", "开放",
            "CLOSED", "已关闭",
            "OVERDUE", "超期");

    /** 保留期限按小时计时，按每天飞行 6 小时折算成飞行日 */
    public static final int HOUR_PER_DAY = 6;

    /** 保留期限上限（飞行日） */
    public static final int MAX_LIMIT_DAY = 120;

    // ---------------- 航材 ----------------
    private static final Map<String, String> LOAN_STATUS = Map.of(
            "OUT", "在借未还",
            "BACK", "已归还");

    // ---------------- 取值方法 ----------------

    public static boolean isValidModel(String model) {
        return model != null && MODEL_LENGTH.containsKey(model);
    }

    public static BigDecimal modelLength(String model) {
        return MODEL_LENGTH.getOrDefault(model, BigDecimal.ZERO);
    }

    public static boolean isValidSizeClass(String sizeClass) {
        return sizeClass != null && SIZE_FIT.containsKey(sizeClass);
    }

    public static String sizeClassName(String sizeClass) {
        return sizeClass == null ? "" : SIZE_CLASS_NAME.getOrDefault(sizeClass, sizeClass);
    }

    /** 机位尺寸等级能否承载该机型 */
    public static boolean bayFits(String sizeClass, String model) {
        if (!isValidSizeClass(sizeClass) || !isValidModel(model)) {
            return false;
        }
        return SIZE_FIT.get(sizeClass).contains(model);
    }

    /** 机位可承载的机型文本（分号分隔） */
    public static String fitTypeOf(String sizeClass) {
        return isValidSizeClass(sizeClass) ? String.join(";", SIZE_FIT.get(sizeClass)) : "";
    }

    public static boolean isValidBayStatus(String status) {
        return status != null && BAY_STATUS.containsKey(status);
    }

    public static String bayStatusName(String status) {
        return status == null ? "" : BAY_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidAirworthy(String airworthy) {
        return airworthy != null && AIRWORTHY.containsKey(airworthy);
    }

    public static String airworthyName(String airworthy) {
        return airworthy == null ? "" : AIRWORTHY.getOrDefault(airworthy, airworthy);
    }

    public static boolean isValidCheckType(String checkType) {
        return checkType != null && CHECK_TYPE.containsKey(checkType);
    }

    public static String checkTypeName(String checkType) {
        return checkType == null ? "" : CHECK_TYPE.getOrDefault(checkType, checkType);
    }

    public static BigDecimal checkMaxPlanHour(String checkType) {
        return CHECK_MAX_PLAN_HOUR.getOrDefault(checkType, BigDecimal.valueOf(120));
    }

    public static boolean isValidCardStatus(String status) {
        return status != null && CARD_STATUS.containsKey(status);
    }

    public static String cardStatusName(String status) {
        return status == null ? "" : CARD_STATUS.getOrDefault(status, status);
    }

    /** 状态机流转是否允许 */
    public static boolean cardFlowAllowed(String from, String to) {
        if (!isValidCardStatus(from) || !isValidCardStatus(to)) {
            return false;
        }
        return CARD_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    public static boolean isValidLimitUnit(String unit) {
        return unit != null && LIMIT_UNIT.containsKey(unit);
    }

    public static String limitUnitName(String unit) {
        return unit == null ? "" : LIMIT_UNIT.getOrDefault(unit, unit);
    }

    public static boolean isValidDefectStatus(String status) {
        return status != null && DEFECT_STATUS.containsKey(status);
    }

    public static String defectStatusName(String status) {
        return status == null ? "" : DEFECT_STATUS.getOrDefault(status, status);
    }

    public static String loanStatusName(String status) {
        return status == null ? "" : LOAN_STATUS.getOrDefault(status, status);
    }

    /**
     * 保留期限折算成天数：DAY 直接取数值，HOUR 按每天飞行 {@value #HOUR_PER_DAY} 小时折算（向上取整）。
     */
    public static int limitToDays(String unit, Integer value) {
        int v = value == null ? 0 : value;
        if ("HOUR".equals(unit)) {
            return (int) Math.ceil(v / (double) HOUR_PER_DAY);
        }
        return v;
    }
}
