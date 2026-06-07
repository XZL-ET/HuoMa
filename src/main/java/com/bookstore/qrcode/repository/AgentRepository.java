package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.Agent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AgentRepository extends JpaRepository<Agent, String> {
    List<Agent> findByOverallStatus(Agent.OverallStatus status);
    List<Agent> findByRole(Agent.AgentRole role);
}
