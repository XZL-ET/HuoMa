package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.SystemConfig;
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
@DisplayName("ServiceTeacherDailyMaxService 服务老师日限管理")
class ServiceTeacherDailyMaxServiceTest {

    @Mock private SystemConfigRepository systemConfigRepo;
    @Mock private QrAgentRepository qrAgentRepo;
    @Mock private EntityManager entityManager;

    @InjectMocks
    private ServiceTeacherDailyMaxService service;

    private void givenFallback(int fallback) {
        ReflectionTestUtils.setField(service, "fallbackDefault", fallback);
    }

    @Test
    @DisplayName("resolveDefault — system_config 有合法值则返回该值")
    void resolveDefaultReturnsConfiguredValue() {
        givenFallback(200);
        SystemConfig c = new SystemConfig();
        c.setConfigKey(ServiceTeacherDailyMaxService.CONFIG_KEY);
        c.setConfigValue("300");
        when(systemConfigRepo.findByConfigKey(ServiceTeacherDailyMaxService.CONFIG_KEY))
                .thenReturn(Optional.of(c));

        assertEquals(300, service.resolveDefault());
    }

    @Test
    @DisplayName("resolveDefault — 无配置则返回兜底值")
    void resolveDefaultFallsBackWhenMissing() {
        givenFallback(200);
        when(systemConfigRepo.findByConfigKey(ServiceTeacherDailyMaxService.CONFIG_KEY))
                .thenReturn(Optional.empty());

        assertEquals(200, service.resolveDefault());
    }

    @Test
    @DisplayName("resolveDefault — 配置值非法则返回兜底值")
    void resolveDefaultFallsBackWhenInvalid() {
        givenFallback(200);
        SystemConfig c = new SystemConfig();
        c.setConfigKey(ServiceTeacherDailyMaxService.CONFIG_KEY);
        c.setConfigValue("abc");
        when(systemConfigRepo.findByConfigKey(ServiceTeacherDailyMaxService.CONFIG_KEY))
                .thenReturn(Optional.of(c));

        assertEquals(200, service.resolveDefault());
    }

    @Test
    @DisplayName("resolveDefault — 配置值非正数则返回兜底值")
    void resolveDefaultFallsBackWhenNonPositive() {
        givenFallback(200);
        SystemConfig c = new SystemConfig();
        c.setConfigKey(ServiceTeacherDailyMaxService.CONFIG_KEY);
        c.setConfigValue("0");
        when(systemConfigRepo.findByConfigKey(ServiceTeacherDailyMaxService.CONFIG_KEY))
                .thenReturn(Optional.of(c));

        assertEquals(200, service.resolveDefault());
    }

    @Test
    @DisplayName("applyToAll — 先建备份表（含 dual）再批量更新，返回受影响行数")
    void applyToAllBacksUpThenUpdates() {
        Query query = mock(Query.class);
        when(entityManager.createNativeQuery(argThat(sql ->
                sql.contains("CREATE TABLE qr_agent_bak_") && sql.contains("role IN ('service','dual')"))))
                .thenReturn(query);
        when(qrAgentRepo.applyDailyMaxToServiceTeachers(300)).thenReturn(3370);

        int affected = service.applyToAll(300);

        assertEquals(3370, affected);

        InOrder inOrder = inOrder(entityManager, qrAgentRepo);
        inOrder.verify(entityManager).createNativeQuery(argThat(sql ->
                sql.contains("CREATE TABLE qr_agent_bak_") && sql.contains("role IN ('service','dual')")));
        inOrder.verify(qrAgentRepo).applyDailyMaxToServiceTeachers(300);
        verify(query).executeUpdate();
    }

    @Test
    @DisplayName("saveDefault — 配置不存在则新建并写入值")
    void saveDefaultCreatesWhenMissing() {
        when(systemConfigRepo.findByConfigKey(ServiceTeacherDailyMaxService.CONFIG_KEY))
                .thenReturn(Optional.empty());

        service.saveDefault(300);

        verify(systemConfigRepo).save(org.mockito.ArgumentMatchers.argThat(c ->
                ServiceTeacherDailyMaxService.CONFIG_KEY.equals(c.getConfigKey())
                        && "300".equals(c.getConfigValue())));
    }

    @Test
    @DisplayName("saveDefault — 配置已存在则更新值")
    void saveDefaultUpdatesWhenPresent() {
        SystemConfig existing = new SystemConfig();
        existing.setConfigKey(ServiceTeacherDailyMaxService.CONFIG_KEY);
        existing.setConfigValue("200");
        when(systemConfigRepo.findByConfigKey(ServiceTeacherDailyMaxService.CONFIG_KEY))
                .thenReturn(Optional.of(existing));

        service.saveDefault(300);

        verify(systemConfigRepo).save(existing);
        assertEquals("300", existing.getConfigValue());
    }

    @Test
    @DisplayName("applyToAll — 非法值（<=0 或 >800）抛异常，不执行备份与更新")
    void applyToAllRejectsInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> service.applyToAll(0));
        assertThrows(IllegalArgumentException.class, () -> service.applyToAll(-1));
        assertThrows(IllegalArgumentException.class, () -> service.applyToAll(801));

        verifyNoInteractions(entityManager, qrAgentRepo);
    }

    @Test
    @DisplayName("saveDefault — 非法值（<=0 或 >800）抛异常，不写入配置")
    void saveDefaultRejectsInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> service.saveDefault(0));
        assertThrows(IllegalArgumentException.class, () -> service.saveDefault(-1));
        assertThrows(IllegalArgumentException.class, () -> service.saveDefault(801));

        verifyNoInteractions(systemConfigRepo);
    }
}
