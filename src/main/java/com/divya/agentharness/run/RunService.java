package com.divya.agentharness.run;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.divya.agentharness.agent.AgentLoop;
import com.divya.agentharness.agent.AgentResult;
import com.divya.agentharness.llm.ChatMessage;
import com.divya.agentharness.llm.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs an investigation and records everything in Postgres as it happens.
 */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final AgentLoop agentLoop;
    private final AgentRunRepository runs;
    private final AgentStepRepository steps;
    private final JsonMapper jsonMapper;
    private final RunQueue queue;

    public RunService(AgentLoop agentLoop, AgentRunRepository runs, AgentStepRepository steps,
                      JsonMapper jsonMapper, RunQueue queue) {
        this.agentLoop = agentLoop;
        this.runs = runs;
        this.steps = steps;
        this.jsonMapper = jsonMapper;
        this.queue = queue;
    }

    /**
     * Accepts a new investigation: saves it as PENDING and puts it on the Kafka to-do list.
     * Returns in milliseconds; a worker does the actual investigation later.
     */
    public AgentRun createRun(String incident) {
        AgentRun run = runs.save(new AgentRun(incident));
        try {
            queue.send(run.getId());
        } catch (Exception e) {
            log.error("Could not queue run {}", run.getId(), e);
            run.finish(RunStatus.FAILED, "Error: could not queue the run: " + e.getMessage(), 0, 0, 0);
            return runs.save(run);
        }
        log.info("Run {} queued", run.getId());
        return run;
    }

    /**
     * Sends a run that seems stuck at PENDING to Kafka again (see RunSweeper).
     * Harmless if the first message did arrive: the worker skips or resumes duplicates.
     */
    public void requeue(AgentRun run) {
        run.markQueued();
        runs.save(run);
        try {
            queue.send(run.getId());
            log.warn("Run {} was still PENDING, re-queued it", run.getId());
        } catch (Exception e) {
            log.error("Could not re-queue run {}; the sweeper will try again", run.getId(), e);
        }
    }

    /**
     * Does the investigation for a queued run. Safe to call more than once for the same run
     * (Kafka delivers at least once): a finished run is skipped, and an interrupted one resumes.
     */
    public AgentRun process(UUID runId) {
        AgentRun run = runs.findById(runId).orElse(null);
        if (run == null) {
            log.warn("Run {} not found, ignoring", runId);
            return null;
        }
        if (run.getStatus() == RunStatus.COMPLETED || run.getStatus() == RunStatus.FAILED
                || run.getStatus() == RunStatus.WAITING_APPROVAL) {
            // Finished, or paused until a human decides (the decision re-queues it).
            log.info("Run {} is {}, skipping this delivery", runId, run.getStatus());
            return run;
        }

        // Whatever was saved before a crash becomes the starting conversation.
        List<AgentStep> saved = steps.findByRunIdOrderBySeq(runId);
        List<ChatMessage> history = saved.stream().map(this::toMessage).toList();
        int tokensBefore = saved.stream().mapToInt(AgentStep::getTokens).sum();
        AtomicInteger seq = new AtomicInteger(saved.isEmpty() ? 0 : saved.get(saved.size() - 1).getSeq());

        run.markRunning();
        runs.save(run);

        try {
            // Each save() commits on its own, so every step is safely in the database
            // before the next one starts.
            AgentResult result = agentLoop.investigate(runId, run.getIncident(), history, tokensBefore, (message, tokens) ->
                    steps.save(new AgentStep(
                            runId,
                            seq.incrementAndGet(),
                            message.role(),
                            message.content(),
                            message.hasToolCalls() ? jsonMapper.writeValueAsString(message.toolCalls()) : null,
                            message.toolCallId(),
                            tokens)));

            RunStatus status = switch (result.outcome()) {
                case ANSWERED -> RunStatus.COMPLETED;
                case STOPPED -> RunStatus.FAILED;
                case WAITING_FOR_APPROVAL -> RunStatus.WAITING_APPROVAL;
            };
            run.finish(status, result.answer(), result.llmCalls(), result.toolCalls(), tokensBefore + result.totalTokens());
        } catch (Exception e) {
            log.error("Run {} failed", runId, e);
            run.finish(RunStatus.FAILED, "Error: " + e.getMessage(), 0, 0, tokensBefore);
        }
        return runs.save(run);
    }

    /** Turns a saved row back into the message the model originally saw or sent. */
    private ChatMessage toMessage(AgentStep step) {
        List<ToolCall> toolCalls = step.getToolCalls() == null ? null
                : jsonMapper.readValue(step.getToolCalls(), new TypeReference<List<ToolCall>>() {});
        return new ChatMessage(step.getRole(), step.getContent(), toolCalls, step.getToolCallId());
    }
}
