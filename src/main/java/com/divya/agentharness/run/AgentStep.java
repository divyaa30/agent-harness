package com.divya.agentharness.run;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One message in an investigation's conversation: one row in agent_steps.
 */
@Entity
@Table(name = "agent_steps")
public class AgentStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // Postgres numbers the rows (BIGSERIAL)
    private Long id;

    private UUID runId;
    private int seq;
    private String role;

    @Column(columnDefinition = "text")
    private String content;

    @Column(columnDefinition = "text")
    private String toolCalls;

    private String toolCallId;
    private Instant createdAt;

    protected AgentStep() {
        // for JPA
    }

    public AgentStep(UUID runId, int seq, String role, String content, String toolCalls, String toolCallId) {
        this.runId = runId;
        this.seq = seq;
        this.role = role;
        this.content = content;
        this.toolCalls = toolCalls;
        this.toolCallId = toolCallId;
        this.createdAt = Instant.now();
    }

    public UUID getRunId() { return runId; }
    public int getSeq() { return seq; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public String getToolCalls() { return toolCalls; }
    public String getToolCallId() { return toolCallId; }
    public Instant getCreatedAt() { return createdAt; }
}
