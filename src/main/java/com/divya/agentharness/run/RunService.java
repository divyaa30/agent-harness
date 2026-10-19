package com.divya.agentharness.run;

import java.util.concurrent.atomic.AtomicInteger;

import com.divya.agentharness.agent.AgentLoop;
import com.divya.agentharness.agent.AgentResult;
import com.divya.agentharness.llm.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
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

    public RunService(AgentLoop agentLoop, AgentRunRepository runs, AgentStepRepository steps, JsonMapper jsonMapper) {
        this.agentLoop = agentLoop;
        this.runs = runs;
        this.steps = steps;
        this.jsonMapper = jsonMapper;
    }

    public AgentRun startAndRun(String incident) {
        AgentRun run = runs.save(new AgentRun(incident));
        run.markRunning();
        runs.save(run);

        // Each save() commits on its own, so every step is safely in the database
        // before the next one starts. That's what will let us resume after a crash.
        AtomicInteger seq = new AtomicInteger();
        try {
            AgentResult result = agentLoop.investigate(incident, message -> steps.save(new AgentStep(
                    run.getId(),
                    seq.incrementAndGet(),
                    message.role(),
                    message.content(),
                    message.hasToolCalls() ? jsonMapper.writeValueAsString(message.toolCalls()) : null,
                    message.toolCallId())));

            run.finish(result.completed() ? RunStatus.COMPLETED : RunStatus.FAILED,
                    result.answer(), result.llmCalls(), result.toolCalls(), result.totalTokens());
        } catch (Exception e) {
            log.error("Run {} failed", run.getId(), e);
            run.finish(RunStatus.FAILED, "Error: " + e.getMessage(), 0, 0, 0);
        }
        return runs.save(run);
    }
}
