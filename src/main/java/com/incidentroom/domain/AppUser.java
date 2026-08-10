package com.incidentroom.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Document(collection = "users")
@CompoundIndexes({
        // A user can have at most one linked identity per OAuth provider; lookups on login go through this.
        @CompoundIndex(name = "oauth_identity_idx", def = "{'oauthProvider': 1, 'oauthProviderId': 1}", unique = true, sparse = true)
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppUser {

    @Id
    private String id;

    @Indexed(unique = true)
    private String email;

    private String displayName;

    /**
     * Lowercased, trimmed copy of displayName used purely for uniqueness enforcement.
     * displayName itself keeps the user's original casing for display (leaderboard, profile,
     * etc.) — Mongo's default unique index is case-sensitive, so without this field "Alice"
     * and "alice" would be treated as distinct and both allowed, defeating the point of a
     * unique leaderboard name. Kept in sync with displayName everywhere it's set (see
     * AuthService.register / updateDisplayName and CustomOAuth2UserService).
     */
    @Indexed(unique = true, sparse = true)
    private String displayNameNormalized;

    /** Null for accounts that only ever authenticated via OAuth. */
    @lombok.ToString.Exclude
    private String passwordHash;

    private boolean emailVerified;

    /** "google" | "github" | null for password-only accounts. Never written when null (see @Field) —
     *  critical for the sparse unique index below: if every password-only user actually persisted
     *  oauthProvider=null/oauthProviderId=null, they'd all collide on the same (null,null) tuple. */
    @Field(write = Field.Write.NON_NULL)
    private String oauthProvider;

    /** The provider's stable subject/user ID — not the email, which can change on their side. */
    @Field(write = Field.Write.NON_NULL)
    private String oauthProviderId;

    /** Google OAuth subject/user ID if linked. */
    @Field(write = Field.Write.NON_NULL)
    private String googleId;

    /** GitHub OAuth user ID if linked. */
    @Field(write = Field.Write.NON_NULL)
    private String githubId;

    @Builder.Default
    private Role role = Role.USER;

    private Instant createdAt;

    private Instant updatedAt;

    /**
     * UTC date (yyyy-MM-dd) of the most recent completed attempt. Used to calculate daily streaks
     * without aggregating the full attempts collection on every resolve. Updated in AttemptService.
     */
    private String lastPlayedDate;

    /**
     * Current consecutive daily-play streak. Incremented each UTC day the user completes at least
     * one attempt; reset to 1 (not 0) when a day is skipped — the first play after a gap starts
     * a new streak rather than resetting to zero, which feels less punishing while still being accurate.
     */
    @Builder.Default
    private int currentStreak = 0;
}