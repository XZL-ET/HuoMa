package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-test.sql")
@DisplayName("CustomerRepository 自定义查询")
class CustomerRepositoryTest {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private TestEntityManager em;

    private Customer c1, c2, c3;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        now = LocalDateTime.now().withNano(0);

        c1 = Customer.builder()
                .externalUserid("wm-abc123")
                .name("测试客户A")
                .avatar("https://example.com/avatar1.jpg")
                .unionid("unionid-001")
                .type(1)
                .addedAgent("agent-001")
                .currentAgent("agent-001")
                .sourceQrId(1L)
                .schoolId("BJ-001")
                .status(Customer.CustomerStatus.active)
                .addTime(now.minusHours(1))
                .build();
        c2 = Customer.builder()
                .externalUserid("wm-def456")
                .name("测试客户B")
                .avatar("https://example.com/avatar2.jpg")
                .unionid("unionid-002")
                .type(2)
                .addedAgent("agent-002")
                .currentAgent("agent-002")
                .sourceQrId(2L)
                .schoolId("SH-002")
                .status(Customer.CustomerStatus.active)
                .addTime(now.minusHours(2))
                .build();
        c3 = Customer.builder()
                .externalUserid("wm-ghi789")
                .name("未知")
                .type(1)
                .addedAgent("agent-001")
                .currentAgent("agent-001")
                .sourceQrId(1L)
                .schoolId("BJ-001")
                .status(Customer.CustomerStatus.deleted)
                .addTime(now.minusDays(1))
                .build();

        c1 = em.persistFlushFind(c1);
        c2 = em.persistFlushFind(c2);
        c3 = em.persistFlushFind(c3);
    }

    @Test
    @DisplayName("search — 6 参数全 null，返回全部")
    void searchAllNull() {
        Page<Customer> page = customerRepository.search(
                null, null, null, null, null, null, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("search — 关键词搜索")
    void searchByKeyword() {
        Page<Customer> page = customerRepository.search(
                "测试客户A", null, null, null, null, null, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getExternalUserid()).isEqualTo("wm-abc123");
    }

    @Test
    @DisplayName("search — 按 schoolId 筛选")
    void searchBySchoolId() {
        Page<Customer> page = customerRepository.search(
                null, "BJ-001", null, null, null, null, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("search — 按 currentAgent 筛选")
    void searchByCurrentAgent() {
        Page<Customer> page = customerRepository.search(
                null, null, "agent-002", null, null, null, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("search — 按状态筛选")
    void searchByStatus() {
        Page<Customer> page = customerRepository.search(
                null, null, null, Customer.CustomerStatus.deleted,
                null, null, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getExternalUserid()).isEqualTo("wm-ghi789");
    }

    @Test
    @DisplayName("search — 时间范围筛选")
    void searchByTimeRange() {
        LocalDateTime start = now.minusHours(3);
        LocalDateTime end = now;
        Page<Customer> page = customerRepository.search(
                null, null, null, null, start, end, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(2); // c1, c2 within range
    }

    @Test
    @DisplayName("findByExternalUserid — 按 externalUserid 查找")
    void findByExternalUserid() {
        assertThat(customerRepository.findByExternalUserid("wm-abc123")).isPresent();
        assertThat(customerRepository.findByExternalUserid("not-exist")).isEmpty();
    }

    @Test
    @DisplayName("existsByExternalUserid — 检查是否存在")
    void existsByExternalUserid() {
        assertThat(customerRepository.existsByExternalUserid("wm-abc123")).isTrue();
        assertThat(customerRepository.existsByExternalUserid("not-exist")).isFalse();
    }

    @Test
    @DisplayName("findNeedingRepair — 查找需要数据修复的客户")
    void findNeedingRepair() {
        // c3: name="未知" → 需修复; c1/c2 有正常名字
        Page<Customer> needRepair = customerRepository.findNeedingRepair(PageRequest.of(0, 10));
        assertThat(needRepair.getTotalElements()).isEqualTo(1);
        assertThat(needRepair.getContent().get(0).getExternalUserid()).isEqualTo("wm-ghi789");
    }

    @Test
    @DisplayName("findTopAdders — 排行榜 Top N 员工")
    void findTopAdders() {
        List<Object[]> top = customerRepository.findTopAdders(
                now.minusDays(2), now.plusHours(1), PageRequest.of(0, 5));
        assertThat(top).isNotEmpty();
        // agent-001 添加了 2 个客户，应排第一
        Object[] first = top.get(0);
        assertThat(first[0]).isEqualTo("agent-001");
        assertThat((Long) first[1]).isEqualTo(2);
    }

    @Test
    @DisplayName("findTopQrCodes — 排行榜 Top N 活码")
    void findTopQrCodes() {
        List<Object[]> top = customerRepository.findTopQrCodes(
                now.minusDays(2), now.plusHours(1), PageRequest.of(0, 5));
        assertThat(top).isNotEmpty();
        Object[] first = top.get(0);
        assertThat(first[0]).isEqualTo(1L); // sourceQrId=1 有 2 个客户
        assertThat((Long) first[1]).isEqualTo(2);
    }

    @Test
    @DisplayName("countDistinctSourceQrByAddTimeBetween — 去重活码数")
    void countDistinctSourceQrByAddTimeBetween() {
        long count = customerRepository.countDistinctSourceQrByAddTimeBetween(
                now.minusDays(2), now.plusHours(1));
        assertThat(count).isEqualTo(2); // qr 1 and 2
    }
}
