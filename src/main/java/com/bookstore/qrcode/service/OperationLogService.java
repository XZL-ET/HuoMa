package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.OperationLog;
import com.bookstore.qrcode.repository.OperationLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 操作审计日志服务。
 * <p>
 * 提供统一的审计日志写入接口，供各业务 Controller 调用。
 * 日志写入在独立事务中执行，不阻塞主业务流程。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogService {

    private final OperationLogRepository operationLogRepository;
    private final ObjectMapper objectMapper;

    /**
     * 记录一条操作日志。
     *
     * @param operator   操作人（登录用户名）
     * @param action     操作类型（如 create/delete/update/sync）
     * @param targetType 操作对象类型（如 qrcode/customer/agent）
     * @param targetId   操作对象 ID
     * @param detail     操作详情描述
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String operator, String action, String targetType, String targetId, String description) {
        try {
            // 使用 ObjectMapper 序列化，正确处理反斜杠、换行符等特殊字符
            String detail = objectMapper.writeValueAsString(Map.of("description", description));
            OperationLog opLog = OperationLog.builder()
                    .operator(operator)
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .detail(detail)
                    .build();
            operationLogRepository.save(opLog);
            log.debug("操作日志已记录: operator={}, action={}, target={}/{}",
                    operator, action, targetType, targetId);
        } catch (Exception e) {
            log.warn("审计日志写入失败（不影响主业务）: operator={}, action={}, target={}/{}",
                    operator, action, targetType, targetId, e);
        }
    }
}
