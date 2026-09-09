package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.CustomerTransfer;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.CustomerTransferRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 转接记录 Controller 单元测试。
 *
 * <p>验证 list 的拆状态字段映射，以及 detail 的失败原因分布统计与
 * failType → LIKE pattern 的传递（分类规则本身已由 {@code TransferFailTypeTest} 覆盖）。</p>
 */
@DisplayName("转接记录 Controller")
class TransferRecordControllerTest {

    private CustomerTransferRepository transferRepo;
    private CustomerRepository customerRepo;
    private QrCodeRepository qrCodeRepo;
    private EmployeeRepository employeeRepo;
    private AgentRepository agentRepo;
    private TransferRecordController controller;

    @BeforeEach
    void setUp() {
        transferRepo = mock(CustomerTransferRepository.class);
        customerRepo = mock(CustomerRepository.class);
        qrCodeRepo = mock(QrCodeRepository.class);
        employeeRepo = mock(EmployeeRepository.class);
        agentRepo = mock(AgentRepository.class);
        controller = new TransferRecordController(
            transferRepo, customerRepo, qrCodeRepo, employeeRepo, agentRepo);
    }

    @Test
    @DisplayName("list：汇总失败状态拆分映射为四列")
    void listMapsSplitStatusColumns() {
        when(transferRepo.summarizeTransfersByQrCode(any(), any()))
            .thenReturn(Collections.singletonList(new Object[]{
                1L, "第一中学", 10L, 5L, 1L, 1L, 2L, 1L, 0L}));

        Model model = new ExtendedModelMap();
        controller.list("today", null, null, model);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) model.getAttribute("items");
        assertThat(items).hasSize(1);
        Map<String, Object> item = items.get(0);
        assertThat(item.get("qrCodeId")).isEqualTo(1L);
        assertThat(item.get("schoolName")).isEqualTo("第一中学");
        assertThat(item.get("newCount")).isEqualTo(10L);
        assertThat(item.get("successCount")).isEqualTo(5L);
        assertThat(item.get("rejectedCount")).isEqualTo(1L);
        assertThat(item.get("timeoutCount")).isEqualTo(1L);
        assertThat(item.get("apiFailedCount")).isEqualTo(2L);
        assertThat(item.get("retryLimitCount")).isEqualTo(1L);
        assertThat(item.get("pendingCount")).isEqualTo(0L);
    }

    @Test
    @DisplayName("detail：失败原因分布统计 + failType 过滤传递 pattern")
    void detailClassifiesDistributionAndPassesFilterPattern() {
        QrCode qr = new QrCode();
        qr.setId(1L);
        qr.setSchoolName("第一中学");
        when(qrCodeRepo.findById(1L)).thenReturn(Optional.of(qr));

        when(transferRepo.findStatusAndFailReasonByQrCodeAndAddTime(eq(1L), any(), any()))
            .thenReturn(List.of(
                new Object[]{CustomerTransfer.TransferStatus.api_failed,
                    "接管员工企微票据过期(errcode=40205)，需重新登录"},
                new Object[]{CustomerTransfer.TransferStatus.api_failed,
                    "接替成员客户数已达上限(errcode=84097)"},
                new Object[]{CustomerTransfer.TransferStatus.timeout,
                    "无接替记录，超时作废"},
                new Object[]{CustomerTransfer.TransferStatus.confirmed, null}
            ));
        when(transferRepo.findPageByQrCodeAndAddTime(
            eq(1L), any(), any(), eq("%errcode=40205%"), any(PageRequest.class)))
            .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(customerRepo.findAllById(any())).thenReturn(Collections.emptyList());
        when(employeeRepo.findByUseridIn(any())).thenReturn(Collections.emptyList());
        when(agentRepo.findAllById(any())).thenReturn(Collections.emptyList());

        Model model = new ExtendedModelMap();
        controller.detail(1L, "today", null, null, "40205", 0, model);

        assertThat(model.getAttribute("successCount")).isEqualTo(1L);
        assertThat(model.getAttribute("failCount")).isEqualTo(3L);
        assertThat(model.getAttribute("pendingCount")).isEqualTo(0L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failDist = (List<Map<String, Object>>) model.getAttribute("failDist");
        Map<String, Long> distMap = new HashMap<>();
        for (Map<String, Object> d : failDist) {
            distMap.put((String) d.get("key"), (Long) d.get("count"));
        }
        assertThat(distMap)
            .containsEntry("40205", 1L)
            .containsEntry("84097", 1L)
            .containsEntry("timeout", 1L)
            .hasSize(3);

        verify(transferRepo).findPageByQrCodeAndAddTime(
            eq(1L), any(), any(), eq("%errcode=40205%"), any(PageRequest.class));
    }

    @Test
    @DisplayName("detail：failType=other 走排除式查询")
    void detailOtherUsesExclusionQuery() {
        QrCode qr = new QrCode();
        qr.setId(1L);
        qr.setSchoolName("第一中学");
        when(qrCodeRepo.findById(1L)).thenReturn(Optional.of(qr));

        when(transferRepo.findStatusAndFailReasonByQrCodeAndAddTime(eq(1L), any(), any()))
            .thenReturn(Collections.singletonList(
                new Object[]{CustomerTransfer.TransferStatus.api_failed,
                    "重试耗尽: connection timeout"}
            ));
        when(transferRepo.findPageByQrCodeAndAddTimeOther(eq(1L), any(), any(), any(PageRequest.class)))
            .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(customerRepo.findAllById(any())).thenReturn(Collections.emptyList());
        when(employeeRepo.findByUseridIn(any())).thenReturn(Collections.emptyList());
        when(agentRepo.findAllById(any())).thenReturn(Collections.emptyList());

        Model model = new ExtendedModelMap();
        controller.detail(1L, "today", null, null, "other", 0, model);

        verify(transferRepo).findPageByQrCodeAndAddTimeOther(
            eq(1L), any(), any(), any(PageRequest.class));
        verify(transferRepo, never()).findPageByQrCodeAndAddTime(
            any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("detail：page 超出范围时回退到最后一页")
    void detailClampsPageToLastPage() {
        QrCode qr = new QrCode();
        qr.setId(1L);
        qr.setSchoolName("第一中学");
        when(qrCodeRepo.findById(1L)).thenReturn(Optional.of(qr));

        when(transferRepo.findStatusAndFailReasonByQrCodeAndAddTime(eq(1L), any(), any()))
            .thenReturn(Collections.emptyList());

        CustomerTransfer t = CustomerTransfer.builder()
            .customerId(10L).fromUserid("a").toUserid("b").build();
        when(transferRepo.findPageByQrCodeAndAddTime(
            eq(1L), any(), any(), isNull(), any(PageRequest.class)))
            .thenAnswer(inv -> {
                PageRequest pr = inv.getArgument(4);
                int n = pr.getPageNumber();
                return n >= 3
                    ? new PageImpl<>(Collections.emptyList(), pr, 150)
                    : new PageImpl<>(List.of(t), pr, 150);
            });
        when(customerRepo.findAllById(any())).thenReturn(Collections.emptyList());
        when(employeeRepo.findByUseridIn(any())).thenReturn(Collections.emptyList());
        when(agentRepo.findAllById(any())).thenReturn(Collections.emptyList());

        Model model = new ExtendedModelMap();
        controller.detail(1L, "today", null, null, null, 5, model);

        @SuppressWarnings("unchecked")
        Page<CustomerTransfer> tp = (Page<CustomerTransfer>) model.getAttribute("transferPage");
        assertThat(tp.getNumber()).isEqualTo(2);
        assertThat(tp.getContent()).hasSize(1);
        assertThat(model.getAttribute("pageStart")).isEqualTo(0);
        assertThat(model.getAttribute("pageEnd")).isEqualTo(2);
    }

}
