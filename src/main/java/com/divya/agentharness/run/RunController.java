package com.divya.agentharness.run;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/runs")
public class RunController {

    private final RunService runService;
    private final AgentRunRepository runs;
    private final AgentStepRepository steps;

    public RunController(RunService runService, AgentRunRepository runs, AgentStepRepository steps) {
        this.runService = runService;
        this.runs = runs;
        this.steps = steps;
    }

    /** Starts an investigation. For now it waits until the agent finishes (S3 makes this asynchronous). */
    @PostMapping
    public RunView start(@Valid @RequestBody StartRunRequest request) {
        AgentRun run = runService.startAndRun(request.incident());
        return RunView.of(run, steps.findByRunIdOrderBySeq(run.getId()));
    }

    /** Shows a saved investigation with every step. */
    @GetMapping("/{id}")
    public RunView get(@PathVariable UUID id) {
        AgentRun run = runs.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No run with id " + id));
        return RunView.of(run, steps.findByRunIdOrderBySeq(id));
    }

    // ----- request and response shapes -----

    public record StartRunRequest(@NotBlank @Size(max = 2000) String incident) {
    }

    public record RunView(UUID id, RunStatus status, String incident, String result,
                          int llmCalls, int toolCalls, int totalTokens,
                          Instant createdAt, Instant updatedAt, List<StepView> steps) {

        static RunView of(AgentRun run, List<AgentStep> steps) {
            return new RunView(run.getId(), run.getStatus(), run.getIncident(), run.getResult(),
                    run.getLlmCalls(), run.getToolCalls(), run.getTotalTokens(),
                    run.getCreatedAt(), run.getUpdatedAt(),
                    steps.stream()
                            .filter(s -> !s.getRole().equals("system"))  // the long instructions; same every run
                            .map(StepView::of)
                            .toList());
        }
    }

    public record StepView(int seq, String role, String content, String toolCalls, Instant at) {

        static StepView of(AgentStep step) {
            return new StepView(step.getSeq(), step.getRole(), step.getContent(), step.getToolCalls(), step.getCreatedAt());
        }
    }
}
