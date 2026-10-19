package com.divya.agentharness.run;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One investigation: one row in the agent_runs table.
 */
@Entity
@Table(name = "agent_runs")
public class AgentRun {

    @Id
    private UUID id;

    @Column(columnDefinition = "text")
    private String incident;

    @Enumerated(EnumType.STRING)
    private RunStatus status;

    @Column(columnDefinition = "text")
    private String result;

    private int llmCalls;
    private int toolCalls;
    private int totalTokens;
    private Instant createdAt;
    private Instant updatedAt;

    protected AgentRun() {
        // for JPA
    }

    public AgentRun(String incident) {
        this.id = UUID.randomUUID();
        this.incident = incident;
        this.status = RunStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    /** Records that the run was (re)sent to Kafka just now. */
    public void markQueued() {
        this.updatedAt = Instant.now();
    }

    /** Back in the queue after a human decision, so a worker resumes it. */
    public void markPending() {
        this.status = RunStatus.PENDING;
        this.updatedAt = Instant.now();
    }

    public void markRunning() {
        this.status = RunStatus.RUNNING;
        this.updatedAt = Instant.now();
    }

    public void finish(RunStatus status, String result, int llmCalls, int toolCalls, int totalTokens) {
        this.status = status;
        this.result = result;
        this.llmCalls = llmCalls;
        this.toolCalls = toolCalls;
        this.totalTokens = totalTokens;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getIncident() { return incident; }
    public RunStatus getStatus() { return status; }
    public String getResult() { return result; }
    public int getLlmCalls() { return llmCalls; }
    public int getToolCalls() { return toolCalls; }
    public int getTotalTokens() { return totalTokens; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
