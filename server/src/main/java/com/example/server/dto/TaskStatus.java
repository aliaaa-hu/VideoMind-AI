package com.example.server.dto;

public record TaskStatus(State state, String result, String message) {

    public enum State {
        NOT_STARTED,
        QUEUED,
        PROCESSING,
        COMPLETED,
        FAILED
    }

    public static TaskStatus of(State state, String message) {
        return new TaskStatus(state, null, message);
    }

    public static TaskStatus completed(String result) {
        return new TaskStatus(State.COMPLETED, result, "Task completed.");
    }

    public static TaskStatus completed(AgentState agentState) {
        String markdown = agentState.result().toMarkdown();
        if (agentState.critique() != null && agentState.critique().passed()) {
            return completed(markdown);
        }
        String warning = "Analysis completed, but some conclusions did not pass Critic verification. Review timestamped evidence manually.";
        return new TaskStatus(State.COMPLETED, "> **Result notice:** " + warning + "\n\n" + markdown, warning);
    }
}
