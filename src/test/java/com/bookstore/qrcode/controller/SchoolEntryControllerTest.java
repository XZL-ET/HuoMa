package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.SchoolAccessLogService;
import com.bookstore.qrcode.service.SchoolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.awt.*;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("SchoolEntryController 学校自助查询")
class SchoolEntryControllerTest {

    private MockMvc mockMvc;
    private SchoolService schoolService;

    @BeforeEach
    void setUp() {
        schoolService = mock(SchoolService.class);
        SchoolAccessLogService accessLogService = mock(SchoolAccessLogService.class);
        QrCodeRepository qrCodeRepo = mock(QrCodeRepository.class);

        SchoolEntryController controller = new SchoolEntryController(
                schoolService, accessLogService, qrCodeRepo,
                new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /s — 返回城市列表页")
    void shouldReturnCityList() throws Exception {
        when(schoolService.getCities()).thenReturn(List.of());

        mockMvc.perform(get("/s"))
                .andExpect(status().isOk())
                .andExpect(view().name("school/cities"));
    }

    @Test
    @DisplayName("GET /s/districts — HTMX 区县列表")
    void shouldReturnDistricts() throws Exception {
        when(schoolService.getDistricts("北京")).thenReturn(List.of());

        mockMvc.perform(get("/s/districts").param("city", "北京"))
                .andExpect(status().isOk())
                .andExpect(view().name("school/districts"));
    }

    @Test
    @DisplayName("GET /s/search — 搜索学校")
    void shouldSearchSchools() throws Exception {
        when(schoolService.searchSchools("第一")).thenReturn(List.of());

        mockMvc.perform(get("/s/search").param("keyword", "第一"))
                .andExpect(status().isOk())
                .andExpect(view().name("school/search-results"));
    }
}
