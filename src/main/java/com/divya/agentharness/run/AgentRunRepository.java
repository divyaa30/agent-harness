package com.divya.agentharness.run;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring writes the SQL for save(), findById() and friends automatically.
 */
public interface AgentRunRepository extends JpaRepository<AgentRun, UUID> {

    /** WHERE status = ? AND updated_at < ? LIMIT 20: the oldest runs that may be stuck. */
    List<AgentRun> findTop20ByStatusAndUpdatedAtBeforeOrderByUpdatedAt(RunStatus status, Instant before);
}
