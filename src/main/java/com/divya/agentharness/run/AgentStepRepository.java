package com.divya.agentharness.run;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentStepRepository extends JpaRepository<AgentStep, Long> {

    /** Spring reads the method name and writes: WHERE run_id = ? ORDER BY seq */
    List<AgentStep> findByRunIdOrderBySeq(UUID runId);
}
