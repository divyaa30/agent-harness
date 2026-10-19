package com.divya.agentharness.run;

public enum RunStatus {
    PENDING,    // created, not started yet
    RUNNING,    // the agent loop is working on it
    COMPLETED,  // the agent gave a final answer
    FAILED      // something went wrong, or the step limit was hit
}
