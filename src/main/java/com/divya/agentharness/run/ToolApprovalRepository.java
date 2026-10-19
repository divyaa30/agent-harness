package com.divya.agentharness.run;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface ToolApprovalRepository extends JpaRepository<ToolApproval, String> {

    List<ToolApproval> findByRunIdOrderByRequestedAt(UUID runId);

    Optional<ToolApproval> findFirstByRunIdAndStatus(UUID runId, String status);

    /**
     * Records a decision only if nobody has decided yet. Returns 0 if someone else got there
     * first, so two people clicking "approve" and "reject" at once can't both win.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE ToolApproval a
               SET a.status = :status, a.decidedBy = :decidedBy, a.reason = :reason, a.decidedAt = :decidedAt
             WHERE a.toolCallId = :toolCallId AND a.status = 'PENDING'
            """)
    int decide(String toolCallId, String status, String decidedBy, String reason, Instant decidedAt);
}
