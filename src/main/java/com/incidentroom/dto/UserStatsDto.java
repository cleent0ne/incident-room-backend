package com.incidentroom.dto;

/** Response shape for GET /api/v1/users/me/stats. averageScore is null (not 0) when the user
 *  has zero completed attempts — 0 would misleadingly read as "average score of zero" rather
 *  than "no data yet," which matters for how the frontend renders an empty state. */
public record UserStatsDto(Long completedCount, Double averageScore) {

    public static UserStatsDto empty() {
        return new UserStatsDto(0L, null);
    }

    public static UserStatsDto from(AttemptStatsProjection projection) {
        if (projection == null) {
            return empty();
        }
        Long count = projection.getCompletedCount() == null ? 0L : projection.getCompletedCount();
        return new UserStatsDto(count, projection.getAverageScore());
    }
}

