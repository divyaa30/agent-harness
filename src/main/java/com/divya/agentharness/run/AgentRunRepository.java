package com.divya.agentharness.run;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring writes the SQL for save(), findById() and friends automatically.
 */
public interface AgentRunRepository extends JpaRepository<AgentRun, UUID> {
}
