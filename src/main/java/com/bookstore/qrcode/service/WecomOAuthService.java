package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.WecomConfig;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 企微 OAuth 网页授权服务。
 * <p>
 * 处理企微 OAuth2.0 授权流程：构造授权 URL → 回调获取 code → 换取 userid → 写入 Session。
 * </p>
 *
 * @author Bookstore Dev
 * @since 2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WecomOAuthService {

    private final WecomApiClient wecomApiClient;
    private final WecomConfig wecomConfig;
    private final EmployeeRepository employeeRepository;

    /** Session 属性名 */
    public static final String SESSION_EMPLOYEE_USERID = "employeeUserid";
    public static final String SESSION_EMPLOYEE_NAME = "employeeName";

    /**
     * 构造授权 URL 并返回。
     *
     * @param redirectUri 回调完整 URL
     * @return 企微 OAuth 授权 URL
     */
    public String buildAuthUrl(String redirectUri) {
        String state = UUID.randomUUID().toString().substring(0, 8);
        return wecomApiClient.buildOAuthUrl(redirectUri, state);
    }

    /**
     * OAuth 回调处理：用 code 换取 userid，在校验员工身份后写入 Session。
     *
     * @param code    OAuth 授权临时 code
     * @param session HTTP Session
     * @return Employee 实体
     * @throws RuntimeException 员工不存在、已离职、或企微 API 调用失败
     */
    public Employee authenticate(String code, HttpSession session) {
        // 1. 用 code 换 userid
        JsonNode result = wecomApiClient.getUserInfo(code);
        String userid = result.has("UserId") ? result.get("UserId").asText() : null;
        if (userid == null || userid.isEmpty()) {
            throw new RuntimeException("企微返回的用户 ID 为空");
        }
        log.info("OAuth 认证成功，userid={}", userid);

        // 2. 校验员工是否在本地通讯录中且在职
        Employee employee = employeeRepository.findByUserid(userid)
            .orElseThrow(() -> new RuntimeException("该企微账号未在系统中注册"));

        if (!employee.getActive()) {
            throw new RuntimeException("该员工已离职，无法访问下载中心");
        }

        // 3. 写入 Session
        session.setAttribute(SESSION_EMPLOYEE_USERID, employee.getUserid());
        session.setAttribute(SESSION_EMPLOYEE_NAME, employee.getName());
        log.info("员工已登录: userid={}, name={}", employee.getUserid(), employee.getName());

        return employee;
    }
}
