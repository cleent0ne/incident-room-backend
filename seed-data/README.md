# Seed scenarios

Three ready-to-import example scenarios, one per difficulty, each covering a distinct root-cause
class:

| File | Difficulty | Root cause |
|---|---|---|
| `cache-stampede-checkout.yaml` | medium | A cache key expires under peak load and every concurrent request stampedes the database at once |
| `bad-deploy-error-spike.yaml` | easy | A deploy introduces a null-pointer bug; error rate step-changes exactly when rollout completes |
| `connection-pool-exhaustion.yaml` | hard | A slow third-party dependency starves a *shared* connection pool, degrading unrelated endpoints too — no obvious single culprit |

Load them with `../scripts/seed-scenarios.sh` (see the main `README.md`'s "Seeding content"
section), or import one manually:

```bash
curl -X POST http://localhost:8080/api/v1/admin/scenarios/import \
  -H 'Content-Type: text/plain' \
  -H "X-CSRF-Token: $CSRF_TOKEN" \
  -b "ir_access=$ADMIN_TOKEN; ir_csrf=$CSRF_TOKEN" \
  --data-binary @seed-data/cache-stampede-checkout.yaml
```

## Schema reference

Field names are `snake_case` on the wire (`ScenarioYamlImportService` maps them to the camelCase
domain model). All three example files above are valid, working references — start from one of
them rather than this table alone.

```yaml
slug: my-scenario-slug          # required — lowercase, hyphenated, ^[a-z0-9]+(-[a-z0-9]+)*$
title: "Human-readable title"
difficulty: medium              # easy | medium | hard
root_cause_class: my-root-cause # free-form, used for catalog filtering
symptom_summary: "What the player sees first."
duration_target_seconds: 600    # drives the resolve-time score bonus

seo:                            # both fields required, both length-capped
  meta_title: "..."             #   <= 60 chars
  meta_description: "..."       #   <= 160 chars
  og_image_path: "/og/foo.png"  #   optional

topology:
  nodes:
    - { id: some-service, type: service, label: "Some Service" }  # type is free-form (service/cache/database/external/component/...)
  edges:
    - { from: some-service, to: some-other-node }

actions:
  investigate:                  # at least one action (investigate or remediate) required overall
    - key: unique_action_key    # required, unique within the scenario
      label: "Button text"      # required
      description: "..."        # optional, shown as detail/tooltip text
      cost_seconds: 60          # added to elapsed time when taken; drives the time-bonus math
  remediate:
    - key: unique_remediate_key
      label: "Button text"
      correctness: correct      # correct | partially_correct | incorrect | harmful
                                 # at least ONE remediate action must be `correct`, or the
                                 # scenario is unsolvable and will be rejected at publish time
      score_points: 100         # can be negative for `harmful` outcomes
      explanation: "..."        # required — shown on the debrief screen after resolving

telemetry:
  - t: 0                        # offset in seconds from attempt start, for display ordering
    type: metric_point          # log | metric_point | trace_span
    source: some-service        # should match a topology node id
    revealed_by: unique_action_key  # omit for always-visible/ambient telemetry; if present,
                                     # MUST match a real investigate action's key or publish fails
    payload: { any: "free-form map, shape is up to the frontend to render by `type`" }

postmortem: |
  Markdown shown on the debrief screen after resolving. Player-facing writeup of what happened,
  why, the fix, and the lesson — this is also effectively your SEO content, so make it real.
```

## Validation gate (enforced at publish time, not import time)

Import always succeeds and saves a draft, regardless of validity — you can iterate freely.
`PATCH .../publish` is the hard gate, and rejects a scenario that:

- has a `slug` that isn't lowercase-hyphenated
- has zero actions, or any action missing a `key`/`label`
- has a `remediate` action with no `explanation`
- has **no** `remediate` action with `correctness: correct` (unsolvable)
- has a telemetry event whose `revealed_by` doesn't match any real `investigate` action key (a
  dangling reference — nothing could ever reveal that event)
- is missing `seo.meta_title`/`meta_description`, or either exceeds its length cap

The validation script run over these three files (checking exactly these rules) is worth re-running
over any new scenario you author before attempting to publish it.