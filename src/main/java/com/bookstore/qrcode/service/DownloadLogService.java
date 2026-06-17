package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.QrDownloadLog;
import com.bookstore.qrcode.entity.DistrictManager;
import com.bookstore.qrcode.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 下载日志服务。
 * <p>
 * 管理活码下载的追踪记录、个人历史查询和全局统计。
 * </p>
 *
 * @author Bookstore Dev
 * @since 2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadLogService {

    private final QrDownloadLogRepository downloadLogRepo;
    private final QrAgentRepository qrAgentRepo;
    private final QrCodeRepository qrCodeRepo;
    private final DistrictManagerRepository districtManagerRepo;

    /**
     * 记录一次下载。
     *
     * @param qrCodeId    活码 ID
     * @param agentUserid 下载员工 userid
     * @param ipAddress   来源 IP
     */
    @Transactional
    public void recordDownload(Long qrCodeId, String agentUserid, String ipAddress) {
        QrDownloadLog log = QrDownloadLog.builder()
            .qrCodeId(qrCodeId)
            .agentUserid(agentUserid)
            .downloadedAt(LocalDateTime.now())
            .ipAddress(ipAddress)
            .build();
        downloadLogRepo.save(log);
    }

    /**
     * 当前员工下载过哪些活码（活码 ID 集合），用于卡片"已下载"标记。
     */
    public Set<Long> getDownloadedQrCodeIds(String agentUserid) {
        return downloadLogRepo.findByAgentUseridOrderByDownloadedAtDesc(agentUserid)
            .stream()
            .map(QrDownloadLog::getQrCodeId)
            .collect(Collectors.toSet());
    }

    /**
     * 某员工下载某活码的次数。
     */
    public long getDownloadCount(Long qrCodeId, String agentUserid) {
        return downloadLogRepo.countByQrCodeIdAndAgentUserid(qrCodeId, agentUserid);
    }

    /**
     * 员工个人下载历史（每次下载一条）。
     */
    public List<Map<String, Object>> getPersonalHistory(String agentUserid) {
        List<QrDownloadLog> logs = downloadLogRepo.findByAgentUseridOrderByDownloadedAtDesc(agentUserid);
        if (logs.isEmpty()) return List.of();

        // 批量加载 QrCode（避免 N+1）
        Set<Long> qrCodeIds = logs.stream()
            .map(QrDownloadLog::getQrCodeId)
            .collect(Collectors.toSet());
        Map<Long, QrCode> qrCodeMap = qrCodeRepo.findAllById(qrCodeIds).stream()
            .collect(Collectors.toMap(QrCode::getId, q -> q));

        // 批量加载 DistrictManager（区县数量有限，全量加载并缓存）
        Map<String, DistrictManager> managerMap = districtManagerRepo.findAll().stream()
            .collect(Collectors.toMap(
                m -> m.getRegionCity() + "|" + m.getRegionDistrict(),
                m -> m));

        List<Map<String, Object>> result = new ArrayList<>();
        for (QrDownloadLog log : logs) {
            QrCode qr = qrCodeMap.get(log.getQrCodeId());
            if (qr == null) continue;

            DistrictManager manager = managerMap.get(qr.getRegionCity() + "|" + qr.getRegionDistrict());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("schoolName", qr.getSchoolName());
            row.put("regionCity", qr.getRegionCity());
            row.put("regionDistrict", qr.getRegionDistrict());
            row.put("managerName", manager != null ? manager.getManagerName() : "—");
            row.put("downloadedAt", log.getDownloadedAt());
            result.add(row);
        }
        return result;
    }

    /**
     * 全局下载统计（管理后台用）。
     * <p>
     * 返回结构：活码 → 绑定员工 → 每人下载状态和次数。
     * </p>
     */
    public Map<String, Object> getGlobalStats(String city, String district, String managerUserid,
                                               String downloadStatus, int page, int size) {
        // 1. 查询活码（带地区筛选）
        // 注：当前数据规模下 findAll + 内存筛选可接受，后续数据增长时可改用 search 方法在 DB 层过滤
        List<QrCode> allQrCodes = qrCodeRepo.findAll();
        List<QrCode> filteredQrCodes = allQrCodes.stream()
            .filter(q -> city == null || city.isEmpty() || q.getRegionCity().equals(city))
            .filter(q -> district == null || district.isEmpty() || q.getRegionDistrict().equals(district))
            .toList();

        // 2. 构建负责人缓存：city+district → DistrictManager
        Map<String, DistrictManager> managerCache = new HashMap<>();
        List<DistrictManager> allManagers = districtManagerRepo.findAll();
        for (DistrictManager m : allManagers) {
            managerCache.put(m.getRegionCity() + "|" + m.getRegionDistrict(), m);
        }

        // 3. 收集所有相关下载日志
        List<Long> qrCodeIds = filteredQrCodes.stream().map(QrCode::getId).toList();
        Map<Long, List<QrDownloadLog>> downloadLogByQrCode = new HashMap<>();
        if (!qrCodeIds.isEmpty()) {
            List<QrDownloadLog> allLogs = downloadLogRepo.findByQrCodeIdIn(qrCodeIds);
            for (QrDownloadLog log : allLogs) {
                downloadLogByQrCode.computeIfAbsent(log.getQrCodeId(), k -> new ArrayList<>()).add(log);
            }
        }

        // 4. 批量加载所有相关接待员（避免 N+1）
        Map<Long, List<QrAgent>> agentsByQrCodeId = new HashMap<>();
        if (!qrCodeIds.isEmpty()) {
            List<QrAgent> allAgents = qrAgentRepo.findByQrCodeIdIn(qrCodeIds);
            for (QrAgent agent : allAgents) {
                agentsByQrCodeId.computeIfAbsent(agent.getQrCodeId(), k -> new ArrayList<>()).add(agent);
            }
        }

        // 5. 构建统计行
        List<Map<String, Object>> rows = new ArrayList<>();
        int totalDownloaded = 0;
        int totalNotDownloaded = 0;

        for (QrCode qr : filteredQrCodes) {
            String key = qr.getRegionCity() + "|" + qr.getRegionDistrict();
            DistrictManager manager = managerCache.get(key);

            // 按负责人筛选
            if (managerUserid != null && !managerUserid.isEmpty()) {
                if (manager == null || !manager.getManagerUserid().equals(managerUserid)) {
                    continue;
                }
            }

            List<QrAgent> agents = agentsByQrCodeId.getOrDefault(qr.getId(), List.of());
            List<QrDownloadLog> logs = downloadLogByQrCode.getOrDefault(qr.getId(), List.of());

            for (QrAgent agent : agents) {
                if (agent.getStatus() == QrAgent.AgentStatus.removed) continue;

                long count = logs.stream()
                    .filter(l -> l.getAgentUserid().equals(agent.getAgentUserid()))
                    .count();
                boolean downloaded = count > 0;

                if (downloadStatus != null) {
                    if ("downloaded".equals(downloadStatus) && !downloaded) continue;
                    if ("not_downloaded".equals(downloadStatus) && downloaded) continue;
                }

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("qrCodeId", qr.getId());
                row.put("schoolName", qr.getSchoolName());
                row.put("regionCity", qr.getRegionCity());
                row.put("regionDistrict", qr.getRegionDistrict());
                row.put("managerName", manager != null ? manager.getManagerName() : "—");
                row.put("agentUserid", agent.getAgentUserid());
                row.put("downloaded", downloaded);
                row.put("downloadCount", count);
                row.put("lastDownloadAt", logs.stream()
                    .filter(l -> l.getAgentUserid().equals(agent.getAgentUserid()))
                    .map(QrDownloadLog::getDownloadedAt)
                    .max(LocalDateTime::compareTo)
                    .orElse(null));
                rows.add(row);

                if (downloaded) totalDownloaded++; else totalNotDownloaded++;
            }
        }

        // 6. 分页
        int total = rows.size();
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, total);
        List<Map<String, Object>> pageRows = fromIndex < total
            ? rows.subList(fromIndex, toIndex) : List.of();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", pageRows);
        result.put("totalPages", Math.max(1, (int) Math.ceil((double) total / size)));
        result.put("currentPage", page);
        result.put("totalRows", total);
        result.put("totalQrCodes", filteredQrCodes.size());
        result.put("totalDownloaded", totalDownloaded);
        result.put("totalNotDownloaded", totalNotDownloaded);
        result.put("totalDownloads", rows.stream().mapToLong(r -> (long) r.get("downloadCount")).sum());
        return result;
    }
}
