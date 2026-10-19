package com.divya.agentharness;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AgentHarnessApplication {

	public static void main(String[] args) {
		// Servers run in UTC: timestamps are stored consistently, and Postgres
		// rejects the legacy "Asia/Calcutta" zone name that Windows JVMs report.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(AgentHarnessApplication.class, args);
	}

}
