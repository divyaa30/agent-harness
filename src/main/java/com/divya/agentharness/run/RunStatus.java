package com.divya.agentharness.run;

public enum RunStatus {
    PENDING,           // queued, waiting for a worker
    RUNNING,           // the agent loop is working on it
    WAITING_APPROVAL,  // paused: a risky tool call needs a human decision
    COMPLETED,         // the agent gave a final answer
    FAILED             // something went wrong, or a limit was hit
}
