package com.incidentroom.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * scenarioSlug is deliberately denormalized from Scenario — see the backend architecture doc §5.3:
 * listing "your attempt history" would otherwise need a second lookup per row, which is exactly
 * the join-shaped cost MongoDB doesn't handle gracefully. It's a stable, rarely-changing field,
 * which is what makes duplicating it an acceptable (idiomatic, here) trade-off.
 *
 * userId is nullable — anonymous play is a first-class supported path, not an afterthought (see
 * the auth architecture doc §7 for how an anonymous attempt later gets claimed by an account).
 *
 * abandonAt exists purely to drive the TTL index below; it's set on creation and cleared to null
 * the moment an attempt completes, which is what keeps completed attempts around indefinitely
 * while still auto-reaping abandoned in-progress sessions — see the class-level note on the field.
 */
@Document(collection = "attempts")
@CompoundIndex(name = "user_scenario_idx", def = "{'userId': 1, 'scenarioId': 1}")
@CompoundIndex(name = "scenario_status_idx", def = "{'scenarioId': 1, 'status': 1}")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Attempt {

    @Id
    private String id;

    private String scenarioId;

    private String scenarioSlug;

    /** Null for anonymous play. */
    private String userId;

    /**
     * An opaque, cryptographically random 256-bit URL-safe Base64 token generated at attempt
     * creation and returned to the caller in the StartAttemptResponse. This token is the sole
     * capability proof for anonymous-play access and claim operations — the MongoDB ObjectId is
     * never treated as a bearer credential. Indexed unique so the repository can look up an
     * attempt directly by token without scanning the collection.
     */
    @Indexed(unique = true, sparse = true)
    @Field(write = Field.Write.NON_NULL)
    private String capabilityToken;

    private AttemptStatus status;

    private Instant startedAt;

    private Instant completedAt;

    private Integer elapsedSeconds;

    @Builder.Default
    private List<AttemptAction> actionsTaken = new ArrayList<>();

    private String chosenRemediationActionKey;

    private Integer finalScore;

    /**
     * Drives the TTL index: MongoDB reaps this document once the current time passes this value.
     * Set to startedAt + 24h at creation; explicitly cleared to null when the attempt completes,
     * at which point a null-valued indexed field means MongoDB's TTL monitor simply skips it —
     * so completed attempts are kept indefinitely, only abandoned in-progress ones expire.
     */
    @Indexed(expireAfterSeconds = 0)
    private Instant abandonAt;

    /**
     * Optional free-text "what do you think is happening?" captured before the player commits
     * to a remediation. Never scored, never shared publicly. Stored only for the player's own
     * reference at debrief — seeing their initial hypothesis alongside the actual answer is the
     * whole point. Null means the player skipped the reflection prompt, which is fine.
     */
    private String reflectionText;
}
