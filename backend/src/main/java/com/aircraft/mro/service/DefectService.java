package com.aircraft.mro.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aircraft.mro.common.BizException;
import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.Defect;
import com.aircraft.mro.domain.MroDict;
import com.aircraft.mro.dto.DefectDtos;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.DefectRepository;
import com.aircraft.mro.repository.ReleaseLockRepository;

/**
 * 模块四：故障保留。
 *
 * <p>规则面：
 * <ol>
 *   <li>缺陷单号唯一</li>
 *   <li>保留期限：DAY 按飞行日直接加天；HOUR 按每天飞 {@value MroDict#HOUR_PER_DAY} 小时折算成飞行日</li>
 *   <li>到期日已过且仍开放的，列表时自动置为超期（OVERDUE）</li>
 *   <li>已关闭的缺陷不能重复关闭</li>
 * </ol>
 */
@Service
public class DefectService {

    private final DefectRepository defectRepository;
    private final AircraftRepository aircraftRepository;
    private final ReleaseService releaseService;
    private final ReleaseLockRepository releaseLockRepository;

    public DefectService(DefectRepository defectRepository, AircraftRepository aircraftRepository,
                         ReleaseService releaseService, ReleaseLockRepository releaseLockRepository) {
        this.defectRepository = defectRepository;
        this.aircraftRepository = aircraftRepository;
        this.releaseService = releaseService;
        this.releaseLockRepository = releaseLockRepository;
    }

    /** 故障变化联动：先锁该机，再让在审放行单失效、已放行单标记漂移 */
    private void lockAndInvalidate(Long aircraftId, String detail) {
        if (releaseLockRepository.lockAircraft(aircraftId).isEmpty()) {
            releaseLockRepository.save(new com.aircraft.mro.domain.ReleaseLock(aircraftId));
            releaseLockRepository.lockAircraft(aircraftId);
        }
        releaseService.afterFactsChanged(aircraftId, detail);
    }

    @Transactional
    public List<Views.DefectView> list(Long aircraftId) {
        List<Defect> defects = aircraftId == null
                ? defectRepository.findAllByOrderByDefectNoAsc()
                : defectRepository.findAllByAircraftIdOrderByDefectNoAsc(aircraftId);
        LocalDate today = LocalDate.now();
        // 先找出开放中已到期的故障涉及的飞机，按 id 升序一次性拿锁（与签字/终审/出位同序，避免交叉死锁）
        java.util.Set<Long> overdueAircraft = new java.util.TreeSet<>();
        for (Defect defect : defects) {
            if ("OPEN".equals(defect.getStatus()) && defect.getDueDate().isBefore(today)) {
                overdueAircraft.add(defect.getAircraftId());
            }
        }
        for (Long acId : overdueAircraft) {
            if (releaseLockRepository.lockAircraft(acId).isEmpty()) {
                releaseLockRepository.save(new com.aircraft.mro.domain.ReleaseLock(acId));
                releaseLockRepository.lockAircraft(acId);
            }
        }
        // 锁后再把到期故障落库为 OVERDUE（页面整行标红），并让该机在审放行单失效
        for (Defect defect : defects) {
            if ("OPEN".equals(defect.getStatus()) && defect.getDueDate().isBefore(today)) {
                defect.setStatus("OVERDUE");
                defect.setUpdatedAt(LocalDateTime.now());
                defectRepository.save(defect);
            }
        }
        for (Long acId : overdueAircraft) {
            releaseService.afterFactsChanged(acId, "有开放故障到达保留期限自动转为超期");
        }
        Map<Long, String> tailMap = new HashMap<>();
        for (Aircraft aircraft : aircraftRepository.findAllByOrderByTailNoAsc()) {
            tailMap.put(aircraft.getId(), aircraft.getTailNo());
        }
        List<Views.DefectView> list = new ArrayList<>();
        for (Defect defect : defects) {
            list.add(toView(defect, tailMap, today));
        }
        return list;
    }

    @Transactional(readOnly = true)
    public List<Views.DefectView> overdue() {
        Map<Long, String> tailMap = new HashMap<>();
        for (Aircraft aircraft : aircraftRepository.findAllByOrderByTailNoAsc()) {
            tailMap.put(aircraft.getId(), aircraft.getTailNo());
        }
        LocalDate today = LocalDate.now();
        List<Views.DefectView> list = new ArrayList<>();
        for (Defect defect : defectRepository.findAllByStatusOrderByDueDateAsc("OVERDUE")) {
            list.add(toView(defect, tailMap, today));
        }
        return list;
    }

    @Transactional
    public Views.DefectView create(DefectDtos.DefectSaveReq req) {
        String defectNo = trim(req.getDefectNo());
        if (defectRepository.existsByDefectNo(defectNo)) {
            throw new BizException("缺陷单号 " + defectNo + " 已存在，不能重复登记");
        }
        Long aircraftId = req.getAircraftId();
        if (aircraftId == null) {
            throw new BizException("请先选择缺陷所属的飞机");
        }
        Aircraft aircraft = aircraftRepository.findById(aircraftId)
                .orElseThrow(() -> new BizException("飞机不存在：id=" + aircraftId));
        String unit = trim(req.getLimitUnit()).toUpperCase();
        if (!MroDict.isValidLimitUnit(unit)) {
            throw new BizException("保留期限单位只能是 DAY（飞行日）或 HOUR（飞行小时），收到的是：" + req.getLimitUnit());
        }
        Integer limitValue = req.getLimitValue() == null ? 0 : req.getLimitValue();
        if (limitValue <= 0) {
            throw new BizException("保留期限数值必须大于 0");
        }
        int days = MroDict.limitToDays(unit, limitValue);
        if (days > MroDict.MAX_LIMIT_DAY) {
            throw new BizException("保留期限折算后为 " + days + " 天，超过上限 " + MroDict.MAX_LIMIT_DAY + " 天，需走工程评估");
        }
        LocalDate foundDate = req.getFoundDate() == null ? LocalDate.now() : req.getFoundDate();
        LocalDate dueDate = foundDate.plusDays(days);
        Defect defect = new Defect();
        defect.setDefectNo(defectNo);
        defect.setAircraftId(aircraft.getId());
        defect.setAtaChapter(trim(req.getAtaChapter()));
        defect.setDescription(trim(req.getDescription()));
        defect.setLimitUnit(unit);
        defect.setLimitValue(limitValue);
        defect.setFoundDate(foundDate);
        defect.setDueDate(dueDate);
        defect.setStatus(dueDate.isBefore(LocalDate.now()) ? "OVERDUE" : "OPEN");
        defect.setReporter(req.getReporter() == null || req.getReporter().isBlank() ? null : req.getReporter().trim());
        defect.setUpdatedAt(LocalDateTime.now());
        // 先锁该机（与签字/终审/出位同序），让在审单失效、已放行单漂移，再落新故障
        lockAndInvalidate(aircraft.getId(), "新增故障 " + defectNo + "（"
                + ("OVERDUE".equals(defect.getStatus()) ? "已超期" : "开放") + "）");
        Defect saved = defectRepository.save(defect);
        return toView(saved, tailNoMap(aircraft), LocalDate.now());
    }

    /** 关闭缺陷：已关闭的不能再关 */
    @Transactional
    public Views.DefectView close(Long id) {
        Defect defect = defectRepository.findById(id)
                .orElseThrow(() -> new BizException("缺陷单不存在：id=" + id));
        if ("CLOSED".equals(defect.getStatus())) {
            throw new BizException("缺陷单 " + defect.getDefectNo() + " 已关闭，不能重复关闭");
        }
        defect.setStatus("CLOSED");
        defect.setUpdatedAt(LocalDateTime.now());
        // 先锁飞机、失效在审单，再落关闭
        lockAndInvalidate(defect.getAircraftId(), "故障 " + defect.getDefectNo() + " 已关闭");
        Defect saved = defectRepository.save(defect);
        return toView(saved, tailNoMap(null), LocalDate.now());
    }

    private Views.DefectView toView(Defect defect, Map<Long, String> tailMap, LocalDate today) {
        boolean overdue = !"CLOSED".equals(defect.getStatus()) && defect.getDueDate().isBefore(today);
        long daysLeft = ChronoUnit.DAYS.between(today, defect.getDueDate());
        return new Views.DefectView(defect.getId(), defect.getDefectNo(), defect.getAircraftId(),
                tailMap.get(defect.getAircraftId()), defect.getAtaChapter(), defect.getDescription(),
                defect.getLimitUnit(), MroDict.limitUnitName(defect.getLimitUnit()), defect.getLimitValue(),
                defect.getLimitValue() + MroDict.limitUnitName(defect.getLimitUnit()),
                defect.getFoundDate(), defect.getDueDate(), defect.getStatus(),
                MroDict.defectStatusName(defect.getStatus()), defect.getReporter(), daysLeft, overdue);
    }

    private Map<Long, String> tailNoMap(Aircraft only) {
        Map<Long, String> map = new HashMap<>();
        if (only != null) {
            map.put(only.getId(), only.getTailNo());
            return map;
        }
        for (Aircraft aircraft : aircraftRepository.findAllByOrderByTailNoAsc()) {
            map.put(aircraft.getId(), aircraft.getTailNo());
        }
        return map;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
