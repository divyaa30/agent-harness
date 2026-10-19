package com.divya.agentharness.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.divya.agentharness.llm.ToolSpec;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Detective step 3: what do the logs say? Our pretend Kibana.
 */
@Component
public class SearchLogsTool implements AgentTool {

    private static final int MAX_PATTERNS = 15;

    private final NamedParameterJdbcTemplate jdbc;

    public SearchLogsTool(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "search_logs",
                "Search application logs in a time window. Similar messages are grouped into one pattern "
                        + "(numbers replaced by N) with a count and first/last time seen. "
                        + "Filter by service, level and text to narrow down.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "from", Map.of("type", "string", "description", "Start time, e.g. 2026-10-15 10:00 (UTC)"),
                                "to", Map.of("type", "string", "description", "End time, e.g. 2026-10-15 11:00 (UTC)"),
                                "service", Map.of("type", "string", "description", "Optional service name, e.g. kyc-service"),
                                "level", Map.of("type", "string", "enum", List.of("INFO", "WARN", "ERROR"),
                                        "description", "Optional log level"),
                                "contains", Map.of("type", "string", "description", "Optional text the message must contain")),
                        "required", List.of("from", "to")));
    }

    @Override
    public String execute(Map<String, Object> args) {
        Map<String, Object> params = new HashMap<>();
        params.put("from", args.get("from"));
        params.put("to", args.get("to"));
        params.put("service", args.get("service"));
        params.put("level", args.get("level"));
        params.put("contains", args.get("contains"));
        params.put("limit", MAX_PATTERNS);

        // Lesson from the detective exercise: messages contain unique application numbers,
        // so grouping by the raw message is useless. Replace every number with N first.
        List<String> lines = jdbc.query("""
                SELECT service, level,
                       regexp_replace(message, '[0-9]+', 'N', 'g') AS pattern,
                       count(*) AS how_many,
                       to_char(min(logged_at), 'YYYY-MM-DD HH24:MI:SS') AS first_seen,
                       to_char(max(logged_at), 'YYYY-MM-DD HH24:MI:SS') AS last_seen
                FROM app_logs
                WHERE logged_at >= CAST(:from AS timestamptz) AND logged_at < CAST(:to AS timestamptz)
                  AND (CAST(:service AS text) IS NULL OR service = :service)
                  AND (CAST(:level AS text) IS NULL OR level = :level)
                  AND (CAST(:contains AS text) IS NULL OR message ILIKE '%' || :contains || '%')
                GROUP BY service, level, pattern
                ORDER BY how_many DESC
                LIMIT :limit
                """, params, (row, i) -> String.format(
                "  [%s] %s x%d (first %s, last %s): %s",
                row.getString("level"), row.getString("service"), row.getInt("how_many"),
                row.getString("first_seen"), row.getString("last_seen"), row.getString("pattern")));

        if (lines.isEmpty()) {
            return "No logs matched.";
        }
        return "Log patterns (most frequent first, max " + MAX_PATTERNS + "):\n" + String.join("\n", lines);
    }
}
