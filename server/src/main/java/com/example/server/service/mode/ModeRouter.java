package com.example.server.service.mode;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.ModeClassification;
import com.example.server.dto.RouteDecision;
import com.example.server.utils.DeepSeekUtils;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 *
 * <ol>
 * </ol>
 */
@Service
public class ModeRouter {

    private static final Logger log = LoggerFactory.getLogger(ModeRouter.class);
    private static final int USER_ROUTES_PER_MINUTE = 10;
    private static final int GLOBAL_ROUTES_PER_MINUTE = 60;

    private final DeepSeekUtils deepSeekUtils;
    private final RedissonClient redissonClient;

    public ModeRouter(DeepSeekUtils deepSeekUtils, RedissonClient redissonClient) {
        this.deepSeekUtils = deepSeekUtils;
        this.redissonClient = redissonClient;
    }

    /**
     */
    public RouteDecision route(String goal) {
        if (goal == null || goal.isBlank()) {
            return new RouteDecision(AnalysisMode.GENERAL, "No analysis goal was provided, so general mode was selected.");
        }
        try {
            ModeClassification classification = deepSeekUtils.classifyMode(goal.trim());
            AnalysisMode mode = AnalysisMode.fromNullable(
                    classification == null ? null : classification.mode());
            String reason = pickReason(classification, mode);
            return new RouteDecision(mode, reason);
        } catch (Exception e) {
            log.warn("Automatic intent routing failed; falling back to GENERAL. goalLength={}", goal.length(), e);
            return new RouteDecision(AnalysisMode.GENERAL, "Intent routing is unavailable, so general mode was selected.");
        }
    }

    /**
     */
    public RouteDecision route(String goal, Long userId) {
        if (!tryAcquireQuota(userId)) {
            return new RouteDecision(
                    AnalysisMode.GENERAL, "Automatic routing is busy, so general mode was selected.");
        }
        return route(goal);
    }

    private boolean tryAcquireQuota(Long userId) {
        if (userId == null) return false;
        try {
            RRateLimiter userLimiter = redissonClient.getRateLimiter(
                    "limit:ai:route:user:" + userId);
            userLimiter.trySetRate(
                    RateType.OVERALL, USER_ROUTES_PER_MINUTE, 1, RateIntervalUnit.MINUTES);
            if (!userLimiter.tryAcquire()) return false;

            RRateLimiter globalLimiter = redissonClient.getRateLimiter("limit:ai:route:global");
            globalLimiter.trySetRate(
                    RateType.OVERALL, GLOBAL_ROUTES_PER_MINUTE, 1, RateIntervalUnit.MINUTES);
            return globalLimiter.tryAcquire();
        } catch (RuntimeException e) {
            log.warn("Automatic intent-routing rate limiter is unavailable; falling back to GENERAL. userId={}", userId, e);
            return false;
        }
    }

    private String pickReason(ModeClassification classification, AnalysisMode mode) {
        if (classification != null
                && classification.reason() != null
                && !classification.reason().isBlank()) {
            return classification.reason().trim();
        }
        return switch (mode) {
            case LEARNING -> "The goal emphasizes learning and review, so learning mode was selected.";
            case REVIEW -> "The goal emphasizes fact-checking and claim review, so review mode was selected.";
            case CREATION -> "The goal emphasizes content repurposing, so creation mode was selected.";
            case GENERAL -> "The goal is general, so general mode was selected.";
        };
    }
}
