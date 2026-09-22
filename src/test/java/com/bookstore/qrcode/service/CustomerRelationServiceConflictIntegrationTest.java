package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerRelation;
import com.bookstore.qrcode.entity.CustomerRelation.RelationStatus;
import com.bookstore.qrcode.repository.CustomerRelationRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 复现生产事故：{@code entityManager.detach(rel)} 在 rel 是新建实体（id=null）且 INSERT
 * 因唯一键冲突失败时，抛 {@code IllegalStateException: cannot generate an EntityKey when
 * id is null}（Hibernate 6 {@code AbstractEntityEntry.getEntityKey()}）。
 *
 * <p>用 {@code @SpyBean} 让首次 findBy 查空（服务走 INSERT 路径），save 打真实 Hibernate，
 * 命中已预置的 (customer, employee) 行，确定性地触发唯一键冲突 + detach(null-id)。
 */
@DataJpaTest
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-test.sql")
@DisplayName("CustomerRelationService 并发插入冲突集成（detach id=null）")
class CustomerRelationServiceConflictIntegrationTest {

    @SpyBean private CustomerRelationRepository relationRepo;
    @Autowired private TestEntityManager em;
    @Autowired private EntityManager entityManager;

    private CustomerRelationService service;
    private Long customerId;

    @BeforeEach
    void setUp() {
        Customer c = em.persistFlushFind(Customer.builder()
                .externalUserid("wm-conflict").name("客户").type(1)
                .addedAgent("recA").currentAgent("recA")
                .sourceQrId(1L).schoolId("SCHOOL-1")
                .status(Customer.CustomerStatus.active).addTime(LocalDateTime.now()).build());
        customerId = c.getId();

        // 预置一行 (customer, recA)，来源字段留空以便观察 COALESCE 回填
        relationRepo.saveAndFlush(CustomerRelation.builder()
                .customerId(customerId).employeeUserid("recA")
                .status(RelationStatus.active).build());

        service = new CustomerRelationService(relationRepo, entityManager);
    }

    @Test
    @DisplayName("新建实体并发插入冲突（id=null）不抛异常，且回填来源字段")
    void newEntityConflictDoesNotThrow() {
        // 预取的赢家行；首次 findBy 强制查空 → 服务走 INSERT；save 打真实 Hibernate 触发唯一键冲突
        CustomerRelation winner = relationRepo.findByCustomerIdAndEmployeeUserid(customerId, "recA").orElseThrow();
        doReturn(Optional.empty())
                .doReturn(Optional.of(winner))
                .when(relationRepo).findByCustomerIdAndEmployeeUserid(customerId, "recA");

        assertThatCode(() -> service.upsertActive(customerId, "recA", 10L, "SCHOOL-2", LocalDateTime.now()))
                .doesNotThrowAnyException();

        ArgumentCaptor<CustomerRelation> captor = ArgumentCaptor.forClass(CustomerRelation.class);
        verify(relationRepo, times(2)).save(captor.capture());
        CustomerRelation savedWinner = captor.getAllValues().get(1); // 第二次 save = 冲突后重查的赢家
        assertThat(savedWinner.getQrCodeId()).isEqualTo(10L);
        assertThat(savedWinner.getSchoolId()).isEqualTo("SCHOOL-2");
    }
}
