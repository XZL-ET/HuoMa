package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.AgentAlert;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.AlertService;
import com.bookstore.qrcode.service.AlertTriageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

import static com.bookstore.qrcode.entity.AgentAlert.AlertStatus.open;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AlertController 告警中心")
class AlertControllerTest {

    @Mock AlertService alertService;
    @Mock AgentRepository agentRepo;
    @Mock QrCodeRepository qrCodeRepo;
    @Mock AlertTriageService triageService;
    @InjectMocks AlertController controller;

    private static AlertTriageService.TriageSummary emptySummary() {
        return new AlertTriageService.TriageSummary(List.of(), List.of(), 0, 0);
    }

    /** 分页查询（size=20）与卡片统计（size=2000）使用不同的 Pageable，借此区分两次 findAlerts。 */
    private static Pageable pageableOfSize(int size) {
        return argThat(p -> p != null && p.getPageSize() == size);
    }

    @Test
    @DisplayName("未传 status → 默认只查 open")
    void shouldDefaultToOpen() {
        when(alertService.findAlerts(eq(open), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Page.empty());
        when(alertService.getAlertStats()).thenReturn(Map.of());
        when(triageService.buildSummary(any())).thenReturn(emptySummary());

        ExtendedModelMap model = new ExtendedModelMap();
        controller.list(null, null, null, null, null, null, 0, 20, model);

        assertThat(model.get("statusFilter")).isEqualTo("open");
        assertThat(model.get("statusParam")).isEqualTo("open");
        verify(alertService).findAlerts(eq(open), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), pageableOfSize(20));
    }

    @Test
    @DisplayName("显式传空 status → 查全部")
    void shouldAllowAllStatus() {
        when(alertService.findAlerts(isNull(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Page.empty());
        when(alertService.findAlerts(eq(open), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Page.empty());
        when(alertService.getAlertStats()).thenReturn(Map.of());
        when(triageService.buildSummary(any())).thenReturn(emptySummary());

        ExtendedModelMap model = new ExtendedModelMap();
        controller.list("", null, null, null, null, null, 0, 20, model);

        assertThat(model.get("statusFilter")).isNull();
        assertThat(model.get("statusParam")).isEqualTo("all");
        verify(alertService).findAlerts(isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), pageableOfSize(20));
    }

    @Test
    @DisplayName("负数页码/超大 size 被钳制为 0 / 200")
    void shouldClampPagingParameters() {
        when(alertService.findAlerts(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Page.empty());
        when(alertService.getAlertStats()).thenReturn(Map.of());
        when(triageService.buildSummary(any())).thenReturn(emptySummary());

        ExtendedModelMap model = new ExtendedModelMap();
        controller.list(open.name(), null, null, null, null, null, -5, 10000, model);

        assertThat(model.get("page")).isEqualTo(0);
        assertThat(model.get("size")).isEqualTo(200);
        verify(alertService).findAlerts(eq(open), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), argThat(p -> p.getPageNumber() == 0 && p.getPageSize() == 200));
    }

    @Test
    @DisplayName("列表页写入 triage / typeOptions / typeLabelMap")
    void shouldExposeTriageModelAttributes() {
        when(alertService.findAlerts(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Page.empty());
        when(alertService.getAlertStats()).thenReturn(Map.of());
        when(triageService.buildSummary(any())).thenReturn(emptySummary());

        ExtendedModelMap model = new ExtendedModelMap();
        controller.list(open.name(), null, null, null, null, null, 0, 20, model);

        assertThat(model.get("triage")).isEqualTo(emptySummary());
        assertThat((List<?>) model.get("typeOptions")).hasSizeGreaterThan(0);
        @SuppressWarnings("unchecked")
        Map<String, String> typeLabelMap = (Map<String, String>) model.get("typeLabelMap");
        assertThat(typeLabelMap).containsEntry("add_fail", "加好友失败");
    }

    @Test
    @DisplayName("归档端点把操作人记为解决人并提示数量")
    void shouldArchiveStale() {
        when(alertService.archiveStale("system")).thenReturn(12);
        RedirectAttributes ra = mock(RedirectAttributes.class);

        String view = controller.archiveStale(null, 0, 20, null, null, null, null, null, null, ra);

        verify(alertService).archiveStale("system");
        verify(ra).addFlashAttribute(eq("message"), contains("12"));
        assertThat(view).startsWith("redirect:/alerts");
    }

    @Test
    @DisplayName("手工归档记真实登录名")
    void shouldAttributeManualArchiveToPrincipal() {
        when(alertService.archiveStale("alice")).thenReturn(1);
        RedirectAttributes ra = mock(RedirectAttributes.class);

        controller.archiveStale(() -> "alice", 0, 20, null, null, null, null, null, null, ra);

        verify(alertService).archiveStale("alice");
    }

    @Test
    @DisplayName("重定向保留「全部状态」并编码特殊字符、钳制负页码")
    void shouldBuildEncodedRedirect() {
        RedirectAttributes ra = mock(RedirectAttributes.class);

        String view = controller.resolve(1L, () -> "admin", -5, 2000, "", null, null, "a&b=c", null, null, ra);

        assertThat(view).isEqualTo("redirect:/alerts?page=0&size=200&status=&agent=a%26b%3Dc");
    }

    @Test
    @DisplayName("超出总页数的请求页码被钳到最后一页，避免页码序列倒序膨胀")
    void shouldClampOutOfRangePageToLastPage() {
        Page<AgentAlert> outOfRange =
            new org.springframework.data.domain.PageImpl<>(List.of(),
                org.springframework.data.domain.PageRequest.of(2_000_000, 20), 21);
        when(alertService.findAlerts(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(outOfRange);
        when(alertService.getAlertStats()).thenReturn(Map.of());
        when(triageService.buildSummary(any())).thenReturn(emptySummary());

        ExtendedModelMap model = new ExtendedModelMap();
        controller.list(open.name(), null, null, null, null, null, 2_000_000, 20, model);

        assertThat(model.get("page")).isEqualTo(1);
        assertThat(model.get("pageStart")).isEqualTo(0);
        assertThat(model.get("pageEnd")).isEqualTo(1);
    }
}
