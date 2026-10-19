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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/runs")
public class RunController {

    private final RunService runService;
    private final ApprovalService approvalService;
    private final AgentRunRepository runs;
    private final AgentStepRepository steps;
    private final ToolApprovalRepository approvals;

    public RunController(RunService runService, ApprovalService approvalService, AgentRunRepository runs,
                         AgentStepRepository steps, ToolApprovalRepository approvals) {
        this.runService = runService;
        this.approvalService = approvalService;
        this.runs = runs;
        this.steps = steps;
        this.approvals = approvals;
    }

    /**
     * Accepts an investigation and replies immediately with 202 Accepted.
     * A background worker runs it; poll GET /runs/{id} to follow progress.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunView start(@Valid @RequestBody StartRunRequest request) {
        AgentRun run = runService.createRun(request.incident());
        return RunView.of(run, List.of(), List.of());
    }

    /** Shows a saved investigation with every step and any approval requests. */
    @GetMapping("/{id}")
    public RunView get(@PathVariable UUID id) {
        AgentRun run = runs.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No run with id " + id));
        return view(run);
    }

    /** A human approves the risky action the run is waiting on. The run resumes and the action runs. */
    @PostMapping("/{id}/approve")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunView approve(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return view(approvalService.decide(id, true, request.decidedBy(), request.reason()));
    }

    /** A human rejects it. The run resumes, and the agent is told not to do it. */
    @PostMapping("/{id}/reject")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunView reject(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason is required to reject");
        }
        return view(approvalService.decide(id, false, request.decidedBy(), request.reason()));
    }

    private RunView view(AgentRun run) {
        return RunView.of(run, steps.findByRunIdOrderBySeq(run.getId()), approvals.findByRunIdOrderByRequestedAt(run.getId()));
    }

    // ----- request and response shapes -----

    public record StartRunRequest(@NotBlank @Size(max = 2000) String incident) {
    }

    public record DecisionRequest(@NotBlank @Size(max = 64) String decidedBy, @Size(max = 1000) String reason) {
    }

    public record RunView(UUID id, RunStatus status, String incident, String result,
                          int llmCalls, int toolCalls, int totalTokens,
                          Instant createdAt, Instant updatedAt,
                          List<ApprovalView> approvals, List<StepView> steps) {

        static RunView of(AgentRun run, List<AgentStep> steps, List<ToolApproval> approvals) {
            return new RunView(run.getId(), run.getStatus(), run.getIncident(), run.getResult(),
                    run.getLlmCalls(), run.getToolCalls(), run.getTotalTokens(),
                    run.getCreatedAt(), run.getUpdatedAt(),
                    approvals.stream().map(ApprovalView::of).toList(),
                    steps.stream()
                            .filter(s -> !s.getRole().equals("system"))  // the long instructions; same every run
                            .map(StepView::of)
                            .toList());
        }
    }

    public record ApprovalView(String toolName, String arguments, String status, String decidedBy, String reason,
                               Instant requestedAt, Instant decidedAt) {

        static ApprovalView of(ToolApproval a) {
            return new ApprovalView(a.getToolName(), a.getArguments(), a.getStatus(), a.getDecidedBy(), a.getReason(),
                    a.getRequestedAt(), a.getDecidedAt());
        }
    }

    public record StepView(int seq, String role, String content, String toolCalls, Instant at) {

        static StepView of(AgentStep step) {
            return new StepView(step.getSeq(), step.getRole(), step.getContent(), step.getToolCalls(), step.getCreatedAt());
        }
    }
}
