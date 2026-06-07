package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.AgentAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface AgentAlertRepository extends JpaRepository<AgentAlert, Long> {
    Page<AgentAlert> findByStatusOrderByCreatedAtDesc(
        AgentAlert.AlertStatus status, Pageable pageable);

    List<AgentAlert> findByAgentUseridAndAlertTypeAndStatusAndCreatedAtAfter(
        String agentUserid, String alertType, AgentAlert.AlertStatus status,
        LocalDateTime after);

    long countByAgentUseridAndAlertTypeAndCreatedAtAfter(
        String agentUserid, String alertType, LocalDateTime after);

    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
    long countBySeverityAndCreatedAtBetween(AgentAlert.AlertSeverity severity,
        LocalDateTime start, LocalDateTime end);
}
