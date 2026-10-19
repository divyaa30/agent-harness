package com.divya.agentharness.tools;

import java.util.List;
import java.util.Map;

import com.divya.agentharness.llm.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The only tool that changes something: rolls a service back to an earlier version.
 * It requires human approval, and it is idempotent: the tool call id is stored as a
 * unique key, so a retry after a crash can't roll back twice.
 */
@Component
public class RollbackDeploymentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(RollbackDeploymentTool.class);

    private final NamedParameterJdbcTemplate jdbc;

    public RollbackDeploymentTool(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "rollback_deployment",
                "Roll a service back to an earlier version. This changes production, so a human must "
                        + "approve it first and the investigation pauses until they decide. Use it only when a "
                        + "deployment of that service is clearly the cause AND the problem is still ongoing "
                        + "(no fix or rollback has been deployed since).",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "service", Map.of("type", "string", "description", "Service to roll back"),
                                "to_version", Map.of("type", "string", "description",
                                        "A version that was deployed before, e.g. 1.8.0"),
                                "reason", Map.of("type", "string", "description",
                                        "One sentence of evidence, shown to the human approver")),
                        "required", List.of("service", "to_version", "reason")));
    }

    @Override
    public boolean requiresApproval() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> args) {
        throw new UnsupportedOperationException("rollback_deployment needs the tool call id as an idempotency key");
    }

    @Override
    public String execute(Map<String, Object> args, String toolCallId) {
        Map<String, Object> params = Map.of(
                "service", String.valueOf(args.get("service")),
                "version", String.valueOf(args.get("to_version")),
                "summary", "Rollback to " + args.get("to_version") + " by the agent (human-approved): " + args.get("reason"),
                "key", toolCallId);

        // Safety: only versions this service actually ran before. The model can't invent one.
        Integer known = jdbc.queryForObject(
                "SELECT count(*) FROM deployments WHERE service = :service AND version = :version", params, Integer.class);
        if (known == null || known == 0) {
            return "ERROR: " + params.get("service") + " was never deployed at version " + params.get("version")
                    + ", so it can't be rolled back to it.";
        }

        // Idempotent: a second attempt with the same tool call id inserts nothing.
        int inserted = jdbc.update("""
                INSERT INTO deployments (service, version, deployed_at, change_summary, idempotency_key)
                VALUES (:service, :version, now(), :summary, :key)
                ON CONFLICT (idempotency_key) DO NOTHING
                """, params);
        if (inserted == 0) {
            log.info("Rollback {} already applied, not repeating it", toolCallId);
            return "Already done: this exact rollback was applied earlier, so it was not repeated.";
        }
        log.warn("ROLLED BACK {} to {} (tool call {})", params.get("service"), params.get("version"), toolCallId);
        return "Rolled back " + params.get("service") + " to version " + params.get("version") + ".";
    }
}
