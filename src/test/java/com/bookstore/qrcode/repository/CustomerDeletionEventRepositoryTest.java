package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.CustomerDeletionEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-test.sql")
@DisplayName("CustomerDeletionEventRepository 删除事件查询")
class CustomerDeletionEventRepositoryTest {

    @Autowired
    private CustomerDeletionEventRepository deletionRepo;

    @Autowired
    private TestEntityManager em;

    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        now = LocalDateTime.now().withNano(0);
    }

    private CustomerDeletionEvent event(String externalUserid, String userid,
                                        CustomerDeletionEvent.Direction direction,
                                        LocalDateTime deletedAt) {
        return CustomerDeletionEvent.builder()
                .externalUserid(externalUserid)
                .userid(userid)
                .direction(direction)
                .deletedAt(deletedAt)
                .build();
    }

    @Test
    @DisplayName("findByDirectionAndDeletedAtBetween — 只返回方向匹配且时间在区间内的事件，按时间升序")
    void findsByDirectionAndTimeRange() {
        em.persist(event("wm-c1", "agent1", CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, now.minusHours(2)));
        em.persist(event("wm-c2", "agent2", CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, now.minusHours(1)));
        em.persist(event("wm-c3", "agent3", CustomerDeletionEvent.Direction.AGENT_DELETED_CUSTOMER, now.minusMinutes(30)));
        em.persist(event("wm-c4", "agent4", CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, now.plusHours(1)));
        em.flush();

        List<CustomerDeletionEvent> result = deletionRepo.findByDirectionAndDeletedAtBetween(
                CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT,
                now.minusHours(3), now);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(CustomerDeletionEvent::getExternalUserid)
                .containsExactly("wm-c1", "wm-c2");
    }

    @Test
    @DisplayName("createdAt 由 @PrePersist 自动填充")
    void fillsCreatedAtOnPersist() {
        CustomerDeletionEvent saved = em.persistFlushFind(
                event("wm-c1", "agent1", CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, now));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("findByDeletedAtBetweenOrderByDeletedAtDesc — 区间内按时间倒序")
    void findsBetweenOrderByDesc() {
        for (int i = 0; i < 5; i++) {
            em.persist(event("wm-c" + i, "agent" + i,
                    CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, now.minusHours(i)));
        }
        em.persist(event("wm-out", "agent",
                CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, now.plusHours(1)));
        em.flush();

        List<CustomerDeletionEvent> result = deletionRepo.findByDeletedAtBetweenOrderByDeletedAtDesc(
                now.minusHours(3), now, PageRequest.of(0, 10));

        assertThat(result).hasSize(4);
        assertThat(result).extracting(CustomerDeletionEvent::getExternalUserid)
                .containsExactly("wm-c0", "wm-c1", "wm-c2", "wm-c3");
        assertThat(result).extracting(CustomerDeletionEvent::getDeletedAt)
                .isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    @DisplayName("findByDeletedAtBetweenOrderByDeletedAtDesc — 分页截断")
    void findsBetweenOrderByDescWithLimit() {
        for (int i = 0; i < 5; i++) {
            em.persist(event("wm-c" + i, "agent" + i,
                    CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, now.minusHours(i)));
        }
        em.flush();

        List<CustomerDeletionEvent> result = deletionRepo.findByDeletedAtBetweenOrderByDeletedAtDesc(
                now.minusHours(10), now, PageRequest.of(0, 2));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(CustomerDeletionEvent::getExternalUserid)
                .containsExactly("wm-c0", "wm-c1");
    }
}
