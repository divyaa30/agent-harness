package com.divya.agentharness.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.divya.agentharness.llm.ToolSpec;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Detective steps 1 and 2: is something wrong, when, and what kind of failure?
 */
@Component
public class GetApplicationStatsTool implements AgentTool {

    private final NamedParameterJdbcTemplate jdbc;

    public GetApplicationStatsTool(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "get_application_stats",
                "Account-opening applications per hour (total and not approved), plus a breakdown of failure "
                        + "reasons. Use first, to see whether customers are affected and when.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "from", Map.of("type", "string", "description", "Start time, e.g. 2026-10-15 08:00 (UTC)"),
                                "to", Map.of("type", "string", "description", "End time, e.g. 2026-10-15 12:00 (UTC)")),
                        "required", List.of("from", "to")));
    }

    @Override
    public String execute(Map<String, Object> args) {
        Map<String, Object> params = new HashMap<>();
        params.put("from", args.get("from"));
        params.put("to", args.get("to"));

        StringBuilder out = new StringBuilder("Applications per hour (UTC):\n");
        jdbc.query("""
                SELECT to_char(date_trunc('hour', created_at), 'YYYY-MM-DD HH24:MI') AS hour,
                       count(*) AS total,
                       count(*) FILTER (WHERE status <> 'APPROVED') AS not_approved
                FROM account_applications
                WHERE created_at >= CAST(:from AS timestamptz) AND created_at < CAST(:to AS timestamptz)
                GROUP BY 1 ORDER BY 1
                """, params, row -> {
            out.append(String.format("  %s  total=%d  not_approved=%d%n",
                    row.getString("hour"), row.getInt("total"), row.getInt("not_approved")));
        });

        out.append("Failure reasons in this window:\n");
        jdbc.query("""
                SELECT failure_reason, status, count(*) AS how_many
                FROM account_applications
                WHERE created_at >= CAST(:from AS timestamptz) AND created_at < CAST(:to AS timestamptz)
                  AND status <> 'APPROVED'
                GROUP BY failure_reason, status ORDER BY how_many DESC
                """, params, row -> {
            out.append(String.format("  %s (%s): %d%n",
                    row.getString("failure_reason"), row.getString("status"), row.getInt("how_many")));
        });
        return out.toString();
    }
}
