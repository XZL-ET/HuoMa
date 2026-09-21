package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerRelation;
import com.bookstore.qrcode.entity.CustomerRelation.RelationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-test.sql")
@DisplayName("CustomerRelationRepository 关系表查询")
class CustomerRelationRepositoryTest {

    @Autowired private CustomerRelationRepository relationRepo;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private TestEntityManager em;

    private Customer c1, c2;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        now = LocalDateTime.now().withNano(0);
        c1 = em.persistFlushFind(Customer.builder()
                .externalUserid("wm-c1").name("客户1").type(1)
                .addedAgent("recA").currentAgent("recA")
                .sourceQrId(1L).schoolId("SCHOOL-1")
                .status(Customer.CustomerStatus.active).addTime(now.minusMinutes(30)).build());
        c2 = em.persistFlushFind(Customer.builder()
                .externalUserid("wm-c2").name("客户2").type(1)
                .addedAgent("recA").currentAgent("recA")
                .sourceQrId(2L).schoolId("SCHOOL-2")
                .status(Customer.CustomerStatus.active).addTime(now.minusMinutes(20)).build());
        // c1 有两个员工归属；c2 一个
        relationRepo.save(CustomerRelation.builder().customerId(c1.getId()).employeeUserid("recA")
                .qrCodeId(1L).schoolId("SCHOOL-1").addTime(now.minusMinutes(30)).status(RelationStatus.active).build());
        relationRepo.save(CustomerRelation.builder().customerId(c1.getId()).employeeUserid("svcA")
                .qrCodeId(1L).schoolId("SCHOOL-1").addTime(now.minusMinutes(29)).status(RelationStatus.active).build());
        relationRepo.save(CustomerRelation.builder().customerId(c2.getId()).employeeUserid("recA")
                .qrCodeId(2L).schoolId("SCHOOL-2").addTime(now.minusMinutes(20)).status(RelationStatus.active).build());
    }

    @Test
    @DisplayName("findByCustomerIdAndEmployeeUserid — 按 (customer, employee) 精确定位")
    void findByCustomerIdAndEmployeeUserid() {
        assertThat(relationRepo.findByCustomerIdAndEmployeeUserid(c1.getId(), "svcA")).isPresent();
        assertThat(relationRepo.findByCustomerIdAndEmployeeUserid(c1.getId(), "nobody")).isEmpty();
    }

    @Test
    @DisplayName("findByCustomerIdAndStatus — 多归属客户有多行")
    void multiOwnershipYieldsMultipleRows() {
        List<CustomerRelation> rows = relationRepo.findByCustomerIdAndStatus(c1.getId(), RelationStatus.active);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(CustomerRelation::getEmployeeUserid)
                .containsExactlyInAnyOrder("recA", "svcA");
    }

    @Test
    @DisplayName("findForInheritance — 按 (接待员, 活码) 定位，多校客户各命中一次")
    void findForInheritanceMultiSchool() {
        // recA 在 SCHOOL-1（qrCodeId=1）下：只命中 c1
        List<Customer> q1 = customerRepository.findForInheritance("recA", 1L,
                now.minusHours(1), now);
        assertThat(q1).extracting(Customer::getExternalUserid).containsExactly("wm-c1");

        // recA 在 SCHOOL-2（qrCodeId=2）下：只命中 c2
        List<Customer> q2 = customerRepository.findForInheritance("recA", 2L,
                now.minusHours(1), now);
        assertThat(q2).extracting(Customer::getExternalUserid).containsExactly("wm-c2");

        // svcA 不是接待员维度（不在本查询范围内）：按 svcA 查询在 qrCodeId=1 命中 c1
        List<Customer> q3 = customerRepository.findForInheritance("svcA", 1L,
                now.minusHours(1), now);
        assertThat(q3).extracting(Customer::getExternalUserid).containsExactly("wm-c1");
    }
}
