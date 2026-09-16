package com.example.server.dto;

/**
 *
 *
 *
 */
public record RouteDecision(AnalysisMode mode, String reason) {

    public RouteDecision {
        if (mode == null) {
            mode = AnalysisMode.GENERAL;
        }
        if (reason == null || reason.isBlank()) {
            reason = "Analyzed in general mode";
        }
    }
}
