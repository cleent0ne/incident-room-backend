package com.incidentroom.dto;

/**
 * Concrete binding target for the completed-count/average-score aggregation run by
 * {@code AttemptService.getStatsForUser} via {@code MongoTemplate.aggregate(...)}.
 *
 * This used to be a derived {@code AttemptRepository.@Aggregation} method returning this record
 * directly. That interface-proxy binding turned out to be unreliable for a global
 * {@code $group: { _id: null, ... }} result (every matched document carries that same
 * {@code _id: null}, not just the empty-result case) — see AttemptRepository's note. Running the
 * aggregation directly via {@code MongoTemplate} and unwrapping with
 * {@code AggregationResults#getUniqueMappedResult()} is the documented-reliable path for this
 * exact shape.
 *
 * Field names must match the aggregation pipeline's {@code $group} output keys exactly.
 */
public record AttemptStatsProjection(Double averageScore, Long completedCount) {

    /** Called by UserStatsDto.from() — keep the getter-style names for compatibility. */
    public Double getAverageScore() { return averageScore; }
    public Long getCompletedCount() { return completedCount; }
}

