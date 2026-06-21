package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.EmployeeSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("AgentController 员工管理")
class AgentControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AgentController controller = new AgentController(
                mock(GlobalAgentPoolRepository.class),
                mock(QrAgentRepository.class),
                mock(QrCodeRepository.class),
                mock(EmployeeSyncService.class),
                mock(EmployeeRepository.class),
                mock(AgentRepository.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @Disabled("Pageable resolution requires WebMvcTest — standalone setup lacks argument resolver")
    @DisplayName("GET /agents — 返回员工列表页")
    void shouldReturnAgentListPage() throws Exception {
        mockMvc.perform(get("/agents"))
                .andExpect(status().isOk())
                .andExpect(view().name("agent/list"));
    }

    @Test
    @DisplayName("POST /agents/sync — 手动同步企微通讯录")
    void shouldSyncFromWecom() throws Exception {
        mockMvc.perform(post("/agents/sync"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/agents"));
    }
}
