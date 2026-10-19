package com.divya.agentharness.tools;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The list of the bank's services, shown to the model up front so it never
 * has to guess a service name.
 */
@Component
public class ServiceCatalog {

    private final NamedParameterJdbcTemplate jdbc;

    public ServiceCatalog(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One line per service: "- kyc-service: Verifies customer identity documents ..." */
    public String describe() {
        List<String> lines = jdbc.query("SELECT name, description FROM services ORDER BY name", Map.of(),
                (row, i) -> "- " + row.getString("name") + ": " + row.getString("description"));
        return String.join("\n", lines);
    }
}
