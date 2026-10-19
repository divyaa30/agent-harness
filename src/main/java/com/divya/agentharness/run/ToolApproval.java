package com.divya.agentharness.run;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A risky tool call waiting for (or decided by) a human: one row in tool_approvals.
 */
@Entity
@Table(name = "tool_approvals")
public class ToolApproval {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    @Id
    private String toolCallId;
    private UUID runId;
    private String toolName;

    @Column(columnDefinition = "text")
    private String arguments;

    private String status;
    private String decidedBy;

    @Column(columnDefinition = "text")
    private String reason;

    private Instant requestedAt;
    private Instant decidedAt;

    protected ToolApproval() {
        // for JPA
    }

    public ToolApproval(String toolCallId, UUID runId, String toolName, String arguments) {
        this.toolCallId = toolCallId;
        this.runId = runId;
        this.toolName = toolName;
        this.arguments = arguments;
        this.status = PENDING;
        this.requestedAt = Instant.now();
    }

    public String getToolCallId() { return toolCallId; }
    public UUID getRunId() { return runId; }
    public String getToolName() { return toolName; }
    public String getArguments() { return arguments; }
    public String getStatus() { return status; }
    public String getDecidedBy() { return decidedBy; }
    public String getReason() { return reason; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getDecidedAt() { return decidedAt; }
}
