package com.divya.agentharness.run;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes live updates (new steps, status changes) to everyone watching a run over
 * Server-Sent Events, instead of them polling GET /runs/{id}.
 *
 * Watchers are kept in memory, so this works for clients connected to the same app instance
 * as the worker. With several instances, the updates would go through a shared channel
 * (for example a Kafka topic or Redis pub/sub) first.
 */
@Component
public class RunEvents {

    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(10);

    private final Map<UUID, List<SseEmitter>> watchers = new ConcurrentHashMap<>();

    /** Opens a stream for a run: replays what already happened, then sends updates as they happen. */
    public SseEmitter watch(AgentRun run, List<AgentStep> savedSteps) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        UUID runId = run.getId();
        // Register before replaying, so nothing slips through in between. A step may then arrive
        // twice; clients can drop duplicates by seq.
        watchers.computeIfAbsent(runId, id -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        emitter.onError(e -> remove(runId, emitter));

        savedSteps.stream().filter(s -> !s.getRole().equals("system")).forEach(s -> send(runId, emitter, "step", RunController.StepView.of(s)));
        send(runId, emitter, "status", StatusEvent.of(run));
        if (isFinished(run)) {
            emitter.complete();
        }
        return emitter;
    }

    public void stepSaved(AgentStep step) {
        if (step.getRole().equals("system")) {
            return;
        }
        forEachWatcher(step.getRunId(), emitter -> send(step.getRunId(), emitter, "step", RunController.StepView.of(step)));
    }

    public void statusChanged(AgentRun run) {
        forEachWatcher(run.getId(), emitter -> {
            send(run.getId(), emitter, "status", StatusEvent.of(run));
            if (isFinished(run)) {
                emitter.complete();
            }
        });
    }

    /** What a status event carries. The result is included once the run has one. */
    public record StatusEvent(RunStatus status, String result) {
        static StatusEvent of(AgentRun run) {
            return new StatusEvent(run.getStatus(), run.getResult());
        }
    }

    private static boolean isFinished(AgentRun run) {
        return run.getStatus() == RunStatus.COMPLETED || run.getStatus() == RunStatus.FAILED;
    }

    private void forEachWatcher(UUID runId, java.util.function.Consumer<SseEmitter> action) {
        List<SseEmitter> list = watchers.get(runId);
        if (list != null) {
            list.forEach(action);
        }
    }

    private void send(UUID runId, SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // The watcher went away (closed the tab, network blip). Forget them.
            remove(runId, emitter);
        }
    }

    private void remove(UUID runId, SseEmitter emitter) {
        watchers.computeIfPresent(runId, (id, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }
}
