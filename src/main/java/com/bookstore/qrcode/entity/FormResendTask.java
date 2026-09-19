package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 补发表单群发任务。
 * <p>
 * 每次「发起群发」按员工分批，为每个员工（或其每 ≤1 万客户的子批）创建一条任务，
 * 记录该员工名下待触达的 external_userid 列表、企微返回的 msgid、失败名单与状态。
 * 群发为半自动：调用 {@code add_msg_template} 后需员工在企微端确认才真正发出，
 * 故任务创建后状态为 {@code created}（待员工确认）。
 * </p>
 *
 * @author Bookstore Dev
 */
@Entity
@Table(name = "form_resend_task")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FormResendTask {

    /** 主键，自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 发送员工企微 userid（群发 sender，即客户当前归属员工） */
    @Column(nullable = false, length = 100)
    private String sender;

    /** 该批客户的 external_userid 列表（JSON 数组），单批 ≤ 1 万 */
    @Column(name = "external_userids", columnDefinition = "JSON", nullable = false)
    private String externalUserids;

    /** 企微群发接口返回的 msgid */
    @Column(length = 100)
    private String msgid;

    /** 企微返回的 fail_list（JSON 数组，未命中/不可触达的客户） */
    @Column(name = "fail_list", columnDefinition = "JSON")
    private String failList;

    /** 覆盖人数（external_userids 数量） */
    @Column(name = "covered_count", nullable = false)
    private Integer coveredCount;

    /** 任务状态：created=已创建待员工确认 / failed=调用失败 */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() { createdAt = updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = LocalDateTime.now(); }
}
