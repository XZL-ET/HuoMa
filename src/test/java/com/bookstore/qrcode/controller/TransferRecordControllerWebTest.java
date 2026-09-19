package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.CustomerTransfer;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.CustomerTransferRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.config.LoginSuccessHandler;
import com.bookstore.qrcode.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 转接记录页面 Thymeleaf 渲染测试。
 *
 * <p>单测只断言 view name 与 model，不覆盖模板渲染；本测试用 {@code @WebMvcTest}
 * 走真实 Thymeleaf 视图解析，兜底两级模板（含失败原因分布下钻）的语法与绑定错误。</p>
 */
@WebMvcTest(TransferRecordController.class)
@Import(SecurityConfig.class)
@DisplayName("转接记录页面渲染")
class TransferRecordControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private CustomerTransferRepository transferRepo;
    @MockBean private CustomerRepository customerRepo;
    @MockBean private QrCodeRepository qrCodeRepo;
    @MockBean private EmployeeRepository employeeRepo;
    @MockBean private AgentRepository agentRepo;
    @MockBean(name = "rateLimitRedisTemplate") private StringRedisTemplate rateLimitRedisTemplate;
    @MockBean private LoginSuccessHandler loginSuccessHandler;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /transfers — 渲染一级汇总页")
    void rendersListPage() throws Exception {
        when(transferRepo.summarizeTransfersByQrCode(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/transfers"))
            .andExpect(status().isOk())
            .andExpect(view().name("transfer/list"))
            .andExpect(content().string(containsString("转接记录")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /transfers/1 — 渲染明细页含失败原因分布")
    void rendersDetailPageWithDistribution() throws Exception {
        QrCode qr = new QrCode();
        qr.setId(1L);
        qr.setSchoolName("第一中学");
        when(qrCodeRepo.findById(1L)).thenReturn(Optional.of(qr));
        when(transferRepo.findStatusAndFailReasonByQrCodeAndAddTime(eq(1L), any(), any()))
            .thenReturn(Collections.singletonList(
                new Object[]{CustomerTransfer.TransferStatus.api_failed,
                    "接管员工企微票据过期(errcode=40205)，需重新登录"}
            ));
        when(transferRepo.findPageByQrCodeAndAddTime(
            eq(1L), any(), any(), isNull(), any(PageRequest.class)))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));
        when(customerRepo.findAllById(any())).thenReturn(List.of());
        when(employeeRepo.findByUseridIn(any())).thenReturn(List.of());
        when(agentRepo.findAllById(any())).thenReturn(List.of());

        mockMvc.perform(get("/transfers/1"))
            .andExpect(status().isOk())
            .andExpect(view().name("transfer/detail"))
            .andExpect(content().string(containsString("转接明细")))
            .andExpect(content().string(containsString("票据过期")));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    @DisplayName("GET /transfers — 操作员无权限，返回 403")
    void rejectsOperator() throws Exception {
        mockMvc.perform(get("/transfers"))
            .andExpect(status().isForbidden());
    }
}
