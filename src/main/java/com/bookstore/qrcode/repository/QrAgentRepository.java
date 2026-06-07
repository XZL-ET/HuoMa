package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.QrAgent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface QrAgentRepository extends JpaRepository<QrAgent, Long> {
    List<QrAgent> findByQrCodeIdOrderBySortOrder(Long qrCodeId);
    List<QrAgent> findByQrCodeIdAndStatus(Long qrCodeId, QrAgent.AgentStatus status);
    Optional<QrAgent> findByQrCodeIdAndAgentUserid(Long qrCodeId, String agentUserid);
    List<QrAgent> findByAgentUserid(String agentUserid);
    List<QrAgent> findByAgentUseridAndStatus(String agentUserid, QrAgent.AgentStatus status);
    List<QrAgent> findByStatus(QrAgent.AgentStatus status);
    void deleteByQrCodeIdAndAgentUserid(Long qrCodeId, String agentUserid);
}
