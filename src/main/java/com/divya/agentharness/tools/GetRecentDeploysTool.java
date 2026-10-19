package com.divya.agentharness.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.divya.agentharness.llm.ToolSpec;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Detective step 4: did anything change around that time?
 */
@Component
public class GetRecentDeploysTool implements AgentTool {

    private final NamedParameterJdbcTemplate jdbc;

    public GetRecentDeploysTool(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "get_recent_deploys",
                "Deployments and config changes in a time window, oldest first. "
                        + "Use to check whether a change lines up with when problems started or stopped.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "from", Map.of("type", "string", "description", "Start time, e.g. 2026-10-15 08:00 (UTC)"),
                                "to", Map.of("type", "string", "description", "End time, e.g. 2026-10-15 12:00 (UTC)"),
                                "service", Map.of("type", "string", "description", "Optional service name")),
                        "required", List.of("from", "to")));
    }

    @Override
    public String execute(Map<String, Object> args) {
        Map<String, Object> params = new HashMap<>();
        params.put("from", args.get("from"));
        params.put("to", args.get("to"));
        params.put("service", args.get("service"));

        List<String> lines = jdbc.query("""
                SELECT to_char(deployed_at, 'YYYY-MM-DD HH24:MI') AS at, service, version, change_summary
                FROM deployments
                WHERE deployed_at >= CAST(:from AS timestamptz) AND deployed_at < CAST(:to AS timestamptz)
                  AND (CAST(:service AS text) IS NULL OR service = :service)
                ORDER BY deployed_at
                """, params, (row, i) -> String.format("  %s  %s %s: %s",
                row.getString("at"), row.getString("service"), row.getString("version"),
                row.getString("change_summary")));

        if (lines.isEmpty()) {
            return "No deployments in this window.";
        }
        return "Deployments (UTC):\n" + String.join("\n", lines);
    }
}
