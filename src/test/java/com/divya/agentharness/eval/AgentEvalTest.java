package com.divya.agentharness.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.divya.agentharness.llm.ToolCall;
import com.divya.agentharness.run.AgentRun;
import com.divya.agentharness.run.AgentRunRepository;
import com.divya.agentharness.run.AgentStep;
import com.divya.agentharness.run.AgentStepRepository;
import com.divya.agentharness.run.RunService;
import com.divya.agentharness.run.RunStatus;
import com.divya.agentharness.run.ToolApproval;
import com.divya.agentharness.run.ToolApprovalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The eval suite: runs every scenario against the REAL model on a fresh copy of the fake
 * bank, scores each answer against its answer key, and writes a report.
 *
 * It costs real tokens, so it only runs when asked:
 *   RUN_EVALS=true ./mvnw test -Dtest=AgentEvalTest
 * Optional: EVAL_ONLY=T1,H3 (a subset), EVAL_PAUSE_SECONDS=20 (spacing for rate limits).
 */
@EnabledIfEnvironmentVariable(named = "RUN_EVALS", matches = "true")
@SpringBootTest(properties = {"agent.sweeper.interval-ms=3600000", "spring.kafka.listener.auto-startup=false"})
@Testcontainers
class AgentEvalTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Container
    @ServiceConnection
    static final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    @Autowired RunService runService;
    @Autowired AgentRunRepository runs;
    @Autowired ToolApprovalRepository approvals;
    @Autowired AgentStepRepository steps;
    @Autowired JsonMapper json;

    record Scenario(String id, String set, String incident, List<String> rootCauseAny, List<List<String>> evidenceAll,
                    List<String> mustNotBlame, String rollback, String rollbackService, String rollbackVersion) {
    }

    record Outcome(Scenario scenario, String status, boolean finished, boolean rightCause, boolean evidence,
                   boolean noRedHerring, boolean rollbackOk, int llmCalls, int toolCalls, int tokens, long millis,
                   String answer, List<String> steps) {
        boolean passed() {
            return finished && rightCause && evidence && noRedHerring && rollbackOk;
        }
    }

    @Test
    void runTheEvalSuite() throws Exception {
        List<Scenario> scenarios = load();
        String only = System.getenv("EVAL_ONLY");
        if (only != null && !only.isBlank()) {
            Set<String> ids = Arrays.stream(only.split(",")).map(String::trim).collect(Collectors.toSet());
            scenarios = scenarios.stream().filter(s -> ids.contains(s.id())).toList();
        }
        long pause = Long.parseLong(System.getenv().getOrDefault("EVAL_PAUSE_SECONDS", "20"));

        List<Outcome> outcomes = new ArrayList<>();
        for (Scenario s : scenarios) {
            Instant start = Instant.now();
            AgentRun run = runService.process(runs.save(new AgentRun(s.incident())).getId());
            Outcome o = score(s, run, approvals.findByRunIdOrderByRequestedAt(run.getId()),
                    Duration.between(start, Instant.now()).toMillis(), trace(run));
            outcomes.add(o);
            System.out.printf("EVAL %-4s %-8s %s  calls=%d tools=%d tokens=%d %ds%n", s.id(), s.set(),
                    o.passed() ? "PASS" : "FAIL", o.llmCalls(), o.toolCalls(), o.tokens(), o.millis() / 1000);
            if (o.answer().toLowerCase(Locale.ROOT).contains("tokens per day")) {
                System.out.println("EVAL stopping early: the provider's daily token limit was reached");
                break;
            }
            Thread.sleep(pause * 1000);
        }
        write(outcomes);
    }

    // ------------------------------------------------------------------ scoring

    static Outcome score(Scenario s, AgentRun run, List<ToolApproval> requested, long millis, List<String> steps) {
        String answer = run.getResult() == null ? "" : run.getResult();
        String text = normalise(answer);
        String rootCause = rootCauseSection(text);
        boolean waiting = run.getStatus() == RunStatus.WAITING_APPROVAL;
        boolean rollbackRequired = "required".equals(s.rollback());
        String proposed = requested.stream().map(a -> normalise(a.getArguments())).collect(Collectors.joining(" "));

        boolean finished = run.getStatus() == RunStatus.COMPLETED || (rollbackRequired && waiting);
        boolean rightCause = rollbackRequired
                ? proposed.contains(s.rollbackService())
                : s.rootCauseAny().stream().anyMatch(rootCause::contains);
        boolean evidence = s.evidenceAll().stream().allMatch(group -> group.stream().anyMatch(text::contains));
        boolean noRedHerring = s.mustNotBlame().stream().noneMatch(rootCause::contains);
        boolean rollbackOk = rollbackRequired
                ? waiting && proposed.contains(s.rollbackService()) && proposed.contains(s.rollbackVersion())
                : requested.isEmpty();

        return new Outcome(s, run.getStatus().name(), finished, rightCause, evidence, noRedHerring, rollbackOk,
                run.getLlmCalls(), run.getToolCalls(), run.getTotalTokens(), millis, answer, steps);
    }

    /**
     * What the agent did, step by step: each tool it asked for (with the inputs, like the time
     * window) and the first lines of what came back. The database is thrown away after the
     * suite, so without this a miss can only be guessed at.
     */
    private List<String> trace(AgentRun run) {
        List<String> lines = new ArrayList<>();
        for (AgentStep step : steps.findByRunIdOrderBySeq(run.getId())) {
            if (step.getToolCalls() != null) {
                for (ToolCall call : json.readValue(step.getToolCalls(), new TypeReference<List<ToolCall>>() {})) {
                    lines.add("→ " + call.function().name() + " " + call.function().arguments());
                }
            } else if ("tool".equals(step.getRole())) {
                String[] result = step.getContent().split("\n");
                String preview = String.join(" | ", Arrays.copyOf(result, Math.min(result.length, 4)));
                lines.add("  ← " + preview + (result.length > 4 ? " | … (" + result.length + " lines)" : ""));
            }
        }
        return lines;
    }

    /** Lower case, markdown stripped, and the unicode dashes models like replaced with "-". */
    static String normalise(String s) {
        return s.toLowerCase(Locale.ROOT).replace("*", "").replace("`", "")
                .replaceAll("[\\u2010-\\u2015\\u2212]", "-").replace(' ', ' ');
    }

    /** The text between "Root cause" and "Evidence"; the whole answer if there is no such section. */
    static String rootCauseSection(String text) {
        int start = text.indexOf("root cause");
        if (start < 0) {
            return text;
        }
        int end = text.indexOf("evidence", start);
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }

    // ------------------------------------------------------------------ report

    private List<Scenario> load() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/evals/scenarios.json")) {
            return json.readValue(in, new TypeReference<List<Scenario>>() {});
        }
    }

    private void write(List<Outcome> outcomes) throws IOException {
        StringBuilder md = new StringBuilder("# Eval results\n\n");
        md.append("Model: real LLM provider. Data: fresh copy of the fake bank. Scored automatically.\n\n");
        md.append("| Set | Scenarios | Passed | Accuracy | Avg model calls | Avg tool calls | Avg tokens | Avg seconds |\n");
        md.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (String set : List.of("tuned", "heldout", "all")) {
            List<Outcome> group = outcomes.stream().filter(o -> set.equals("all") || o.scenario().set().equals(set)).toList();
            if (group.isEmpty()) {
                continue;
            }
            long passed = group.stream().filter(Outcome::passed).count();
            md.append(String.format("| %s | %d | %d | %.0f%% | %.1f | %.1f | %.0f | %.0f |%n", set, group.size(), passed,
                    100.0 * passed / group.size(),
                    group.stream().mapToInt(Outcome::llmCalls).average().orElse(0),
                    group.stream().mapToInt(Outcome::toolCalls).average().orElse(0),
                    group.stream().mapToInt(Outcome::tokens).average().orElse(0),
                    group.stream().mapToLong(Outcome::millis).average().orElse(0) / 1000.0));
        }
        md.append("\n| Id | Set | Result | Finished | Right cause | Evidence | No red herring | Rollback correct | Model calls | Tokens |\n");
        md.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Outcome o : outcomes) {
            md.append(String.format("| %s | %s | %s | %s | %s | %s | %s | %s | %d | %d |%n", o.scenario().id(), o.scenario().set(),
                    o.passed() ? "PASS" : "FAIL", mark(o.finished()), mark(o.rightCause()), mark(o.evidence()),
                    mark(o.noRedHerring()), mark(o.rollbackOk()), o.llmCalls(), o.tokens()));
        }
        md.append("\n## Answers\n");
        for (Outcome o : outcomes) {
            md.append("\n### ").append(o.scenario().id()).append(" (").append(o.status()).append(")\n\n")
                    .append("> ").append(o.scenario().incident()).append("\n\n")
                    .append(o.answer().replace("\n", "\n\n")).append("\n");
            if (!o.steps().isEmpty()) {
                md.append("\n<details><summary>Steps</summary>\n\n```\n")
                        .append(String.join("\n", o.steps())).append("\n```\n\n</details>\n");
            }
        }
        Path dir = Path.of("target", "eval");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("report.md"), md.toString());
        Files.writeString(dir.resolve("results.json"), json.writeValueAsString(outcomes));
        System.out.println("EVAL report written to " + dir.resolve("report.md").toAbsolutePath());
    }

    private static String mark(boolean ok) {
        return ok ? "yes" : "**no**";
    }
}
