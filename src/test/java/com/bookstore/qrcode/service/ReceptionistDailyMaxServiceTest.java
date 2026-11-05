package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.SystemConfig;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.SystemConfigRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReceptionistDailyMaxService 接待员日限管理")
class ReceptionistDailyMaxServiceTest {

    @Mock private SystemConfigRepository systemConfigRepo;
    @Mock private QrAgentRepository qrAgentRepo;
    @Mock private GlobalAgentPoolRepository poolRepo;
    @Mock private EntityManager entityManager;

    @InjectMocks
    private ReceptionistDailyMaxService service;

    private void givenFallback(int fallback) {
        ReflectionTestUtils.setField(service, "fallbackDefault", fallback);
    }

    private SystemConfig config(String value) {
        SystemConfig c = new SystemConfig();
        c.setConfigKey(ReceptionistDailyMaxService.CONFIG_KEY);
        c.setConfigValue(value);
        return c;
    }

    @Test
    @DisplayName("resolveDefault — system_config 有合法值则返回该值")
    void resolveDefaultReturnsConfiguredValue() {
        givenFallback(150);
        when(systemConfigRepo.findByConfigKey(ReceptionistDailyMaxService.CONFIG_KEY))
                .thenReturn(Optional.of(config("200")));

        assertEquals(200, service.resolveDefault());
    }

    @Test
    @DisplayName("resolveDefault — 无配置则返回兜底值")
    void resolveDefaultFallsBackWhenMissing() {
        givenFallback(150);
        when(systemConfigRepo.findByConfigKey(ReceptionistDailyMaxService.CONFIG_KEY))
                .thenReturn(Optional.empty());

        assertEquals(150, service.resolveDefault());
    }

    @Test
    @DisplayName("resolveDefault — 配置值非法或非正数则返回兜底值")
    void resolveDefaultFallsBackWhenInvalid() {
        givenFallback(150);
        when(systemConfigRepo.findByConfigKey(ReceptionistDailyMaxService.CONFIG_KEY))
                .thenReturn(Optional.of(config("abc")));
        assertEquals(150, service.resolveDefault());

        when(systemConfigRepo.findByConfigKey(ReceptionistDailyMaxService.CONFIG_KEY))
                .thenReturn(Optional.of(config("0")));
        assertEquals(150, service.resolveDefault());
    }

    @Test
    @DisplayName("applyToAll — 先备份两处数据，再同时更新全局池与活码关联")
    void applyToAllBacksUpThenUpdatesBothTables() {
        Query poolBackup = mock(Query.class);
        Query qrAgentBackup = mock(Query.class);
        when(entityManager.createNativeQuery(argThat(sql -> sql != null
                && sql.contains("CREATE TABLE global_agent_pool_bak_") && sql.contains("role = 'receptionist'"))))
                .thenReturn(poolBackup);
        when(entityManager.createNativeQuery(argThat(sql -> sql != null
                && sql.contains("CREATE TABLE qr_agent_bak_recept_") && sql.contains("role = 'receptionist'"))))
                .thenReturn(qrAgentBackup);
        when(poolRepo.applyDailyMaxToReceptionists(200)).thenReturn(500);
        when(qrAgentRepo.applyDailyMaxToReceptionists(200)).thenReturn(1200);

        int affected = service.applyToAll(200);

        assertEquals(1200, affected);
        verify(poolBackup).executeUpdate();
        verify(qrAgentBackup).executeUpdate();

        InOrder inOrder = inOrder(poolRepo, qrAgentRepo);
        inOrder.verify(poolRepo).applyDailyMaxToReceptionists(200);
        inOrder.verify(qrAgentRepo).applyDailyMaxToReceptionists(200);
    }

    @Test
    @DisplayName("saveDefault — 配置不存在则新建并写入值")
    void saveDefaultCreatesWhenMissing() {
        when(systemConfigRepo.findByConfigKey(ReceptionistDailyMaxService.CONFIG_KEY))
                .thenReturn(Optional.empty());

        service.saveDefault(200);

        verify(systemConfigRepo).save(argThat(c ->
                ReceptionistDailyMaxService.CONFIG_KEY.equals(c.getConfigKey())
                        && "200".equals(c.getConfigValue())));
    }

    @Test
    @DisplayName("saveDefault — 配置已存在则更新值")
    void saveDefaultUpdatesWhenPresent() {
        SystemConfig existing = config("150");
        when(systemConfigRepo.findByConfigKey(ReceptionistDailyMaxService.CONFIG_KEY))
                .thenReturn(Optional.of(existing));

        service.saveDefault(200);

        verify(systemConfigRepo).save(existing);
        assertEquals("200", existing.getConfigValue());
    }

    @Test
    @DisplayName("非法值（<=0 或 >800）：不备份、不更新、不写配置")
    void rejectsInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> service.applyToAll(0));
        assertThrows(IllegalArgumentException.class, () -> service.applyToAll(-1));
        assertThrows(IllegalArgumentException.class, () -> service.applyToAll(801));

        verifyNoInteractions(entityManager, qrAgentRepo, poolRepo);
    }

    @Test
    @DisplayName("saveDefault 非法值抛异常，不写入配置")
    void saveDefaultRejectsInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> service.saveDefault(0));
        assertThrows(IllegalArgumentException.class, () -> service.saveDefault(801));

        verifyNoInteractions(systemConfigRepo);
    }
}
