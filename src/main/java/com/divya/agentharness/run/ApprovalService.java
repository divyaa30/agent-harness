package com.divya.agentharness.run;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.divya.agentharness.agent.ApprovalGate;
import com.divya.agentharness.llm.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stores human decisions about risky tool calls, and resumes the paused run once one is made.
 */
@Service
public class ApprovalService implements ApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ToolApprovalRepository approvals;
    private final AgentRunRepository runs;
    private final RunQueue queue;

    public ApprovalService(ToolApprovalRepository approvals, AgentRunRepository runs, RunQueue queue) {
        this.approvals = approvals;
        this.runs = runs;
        this.queue = queue;
    }

    @Override
    public Optional<Decision> decisionFor(String toolCallId) {
        return approvals.findById(toolCallId)
                .filter(a -> !a.getStatus().equals(ToolApproval.PENDING))
                .map(a -> new Decision(a.getStatus().equals(ToolApproval.APPROVED), a.getDecidedBy(), a.getReason()));
    }

    @Override
    public void requestApproval(UUID runId, ToolCall call) {
        if (approvals.existsById(call.id())) {
            return;  // already requested, e.g. the same message was delivered twice
        }
        approvals.save(new ToolApproval(call.id(), runId, call.function().name(), call.function().arguments()));
        log.warn("Run {} is waiting for approval: {} {}", runId, call.function().name(), call.function().arguments());
    }

    /**
     * A human approves or rejects the run's pending tool call. The run goes back on the Kafka
     * queue, and the worker resumes it: running the tool if approved, or telling the model it
     * was rejected.
     */
    public AgentRun decide(UUID runId, boolean approve, String decidedBy, String reason) {
        AgentRun run = runs.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No run with id " + runId));
        if (run.getStatus() != RunStatus.WAITING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run is " + run.getStatus() + ", not waiting for approval");
        }
        ToolApproval pending = approvals.findFirstByRunIdAndStatus(runId, ToolApproval.PENDING)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No pending approval for this run"));

        String status = approve ? ToolApproval.APPROVED : ToolApproval.REJECTED;
        if (approvals.decide(pending.getToolCallId(), status, decidedBy, reason, Instant.now()) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Someone else already decided this approval");
        }
        log.warn("Run {}: {} {} by {}", runId, pending.getToolName(), status, decidedBy);

        run.markPending();
        runs.save(run);
        try {
            queue.send(runId);
        } catch (Exception e) {
            // The decision is saved and the run is PENDING, so the sweeper will re-queue it.
            log.error("Could not queue run {} after the decision; the sweeper will retry", runId, e);
        }
        return run;
    }
}
