# ADR-0001: SecuritySystems-LLM ⊣ Security Systems Governor architecture

## Status

Accepted. `cloud-itonami-isic-8020` promoted from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

`cloud-itonami-isic-8020` was scaffolded early as a `:blueprint`-tier
repository -- `README.md`/`docs/business-model.md`/`docs/operator-
guide.md`/`blueprint.edn` were published, but no `deps.edn`/`src`/
`test` ever existed. This ADR records the governed-actor architecture
that fills in that pre-existing blueprint with real, tested code,
following the same langgraph StateGraph + independent Governor +
Phase 0→3 rollout pattern established by `cloud-itonami-isic-6511`
(life insurance) and applied across many prior siblings, most closely
`cloud-itonami-isic-4912` (freight rail transport), whose four-op
closed-allowlist / propose-only / self-trip-test discipline this
build mirrors almost exactly, substituting the security-systems-
services domain.

## Scope decision: operations-coordination actor, never alarm-response dispatch or access-control override

The pre-existing `README.md` `Scope note` already draws a careful
distinction: `cloud-itonami-isic-8010` covers PERSONNEL-based
security (guards/patrols); this repository is the SEPARATE, distinctly
licensed business of ELECTRONIC security systems -- installing,
maintaining and monitoring alarms, CCTV and access-control equipment.
This R0 build implements a NARROWER, explicitly-scoped slice of the
pre-existing README's aspirational business (robotics-assisted
installation-support, monitoring-center integration, full dispatch
workflow): a security-systems-services OPERATIONS COORDINATION actor
with a closed, four-member op allowlist:

- `:log-monitoring-record` -- client-site/monitored-system (alarm/
  CCTV/access-control) data logging
- `:schedule-installation-operation` -- installation/maintenance
  scheduling proposal
- `:flag-security-concern` -- surfaces a system-fault/tamper/
  unresolved-alarm concern; ALWAYS escalates
- `:coordinate-equipment-supply` -- hardware procurement coordination

This actor is explicitly NOT the alarm-response dispatcher and NOT
the access-control-override authority -- actual alarm-response
dispatch (deciding to dispatch police/fire in response to an alarm)
is always a human/emergency-services decision, never this actor's,
and neither is finalizing an access-control-override decision. Every
proposal it emits carries a literal `:effect :propose` and an
`:action` drawn from a four-member closed allowlist
(`secsys.governor/allowed-actions`) -- a proposal to directly finalize
an alarm-response-dispatch decision or an access-control-override
decision is not merely disallowed by policy, it cannot be represented
in this allowlist at all. Broader capability-library integration
(`kotoba-lang/robotics` missions/telemetry, `kotoba-lang/labor`
technician dispatch, as named in the pre-existing README `Capability
layer` section) remains an explicit follow-up, not attempted in this
R0 slice.

## Decision

### Decision 1: TWO independent layers block any alarm-response-dispatch or access-control-override proposal

1. **Structural**: `action-allowlist-violations` hard-blocks any
   `:action` outside the four-member `secsys.governor/allowed-actions`
   set -- a finalizing action literally cannot be represented.
   `op-allowlist-violations` does the same for `:op`.
2. **Textual**: `scope-exclusion-violations` scans the proposal's own
   rationale/summary for a small set of finalization/execution ACTION
   phrases (see Decision 2) -- catches a proposal that merely NAMES a
   forbidden act in its prose even without a matching `:action`.

Both are HARD, permanent, un-overridable blocks -- a human approver
never even sees them (HOLD never reaches `:request-approval`).

### Decision 2: scope-exclusion terms are phrased as ACTIONS, never bare nouns -- a fleet-wide self-tripping bug class, fixed by construction AND by test

Multiple sibling agents in this fleet have independently discovered
and fixed the SAME bug: a governor's own scope-exclusion term list
phrased as a bare noun (e.g. "response", "dispatch", "override")
accidentally matches inside the mock advisor's OWN default rationale/
disclaimer text for a legitimate, allowed proposal -- causing the
actor to self-block on its own happy path. Every disclaimer in
`secsys.secsysllm` DENIES having alarm-response-dispatch/access-
control-override authority ("does not authorize any alarm-response
dispatch", "does not decide any access-control configuration change")
using wording deliberately DIFFERENT from the full finalization-action
phrases in `secsys.governor/scope-exclusion-actions` ("dispatch
police response for this alarm", "override the access-control system
for this site") -- phrased as the complete action, not a noun a
denial sentence would also contain. `test/secsys/
governor_self_trip_test.clj` is the actual guarantee, not wording
care alone: it runs the default mock advisor's `infer` across every
op and every seeded site (including the permit/open-concern/already-
open/no-spec-basis branches) and asserts none of the resulting
proposals trip `scope-exclusion-violations`.

### Decision 3: `:effect` is a literal, uniform `:propose` -- a directly-testable structural invariant

`:effect` is ALWAYS the literal keyword `:propose` (asserted by
`effect-not-propose-violations`, HARD/unconditional), and a separate
`:action` key carries the concrete mutation (`:site/log`/`:site/mark-
scheduled`/`:site/flag-security-concern`/`:site/mark-supply-
coordinated`). This makes "this actor never actuates" a literal,
type-checkable field value rather than an implicit convention.

### Decision 4: "record must be independently verified/registered before ANY action" applies to all three non-registration ops, not only the highest-stakes one

`record-not-verified-violations` gates `:schedule-installation-
operation`, `:flag-security-concern`, AND `:coordinate-equipment-
supply` alike on the subject site's own `:registered?` fact (set only
by a committed `:log-monitoring-record` with a valid spec-basis
citation). This matches this vertical's own hard invariant text
literally ("a system/client-site record must be independently
verified/registered before any action").

### Decision 5: `:flag-security-concern` always escalates -- TWO independent layers, matching every sibling's real-actuation discipline

`secsys.governor/high-stakes` includes `:security/flag-concern`
(confidence/actuation gate always escalates), AND `secsys.phase/
phases` never includes `:flag-security-concern` in any phase's
`:auto` set (structural). Both `secsys.phase-test` and `secsys.
governor-contract-test` assert this independently.
`:schedule-installation-operation`/`:coordinate-equipment-supply` get
the same double-guard, for the same reason this actor coordinates but
never authorizes.

### Decision 6: dedicated double-actuation-guard booleans

`:scheduled?`/`:supply-coordination-open?` are dedicated booleans on
the `site` record, never a single `:status` value -- the same
discipline every prior governor's guards establish.

### Decision 7: hand-rolled `enc`/`dec*` EDN-blob codec, not `kotoba-lang/langchain-store`

`kotoba-lang/langchain-store` (ADR-2607141600) is the newer shared
substrate for this codec + identity-schema + entity field-spec
pattern, and is the preferred path for NEW stores. This build instead
mirrors `cloud-itonami-isic-4912`'s own hand-rolled `railfreight.
store` exactly, to minimize dependency-resolution risk from combining
two different `langchain`/`langchain-clj` coordinate families on one
classpath while this actor's own CI/test path only exercises
`-M:test` (no `:dev` override). Migrating `secsys.store` to
`langchain-store` is a reasonable, low-risk follow-up once touched
again, per this workspace's own "touched, migrate incrementally"
policy -- not attempted here to keep this R0 build on the most-proven
path.

### Decision 8: Store protocol, MemStore + DatomicStore parity

`secsys.store/Store` is implemented by both `MemStore` (atom-backed,
default for dev/tests/demo) and `DatomicStore` (`langchain.db`-
backed), proven to satisfy the same contract in `test/secsys/
store_contract_test.clj`.

## Alternatives considered

- **Naming the mutation-routing key `:effect` directly** (matching
  the naming style some sibling actors use for their concrete SSoT
  mutation keyword). Rejected: this actor's own domain design
  explicitly states "all `:effect :propose`" as a closed-allowlist
  property of every op, so `:effect` is reserved for that literal,
  uniform value; `:action` carries the concrete mutation instead.
- **A single combined op that both logs and schedules.** Rejected:
  this actor's closed op allowlist is fixed at exactly four members by
  its own domain design; `:log-monitoring-record` does both the patch
  normalization AND the spec-basis citation in one proposal, and
  scheduling is always a separate, always-escalating op.
- **Adopting `kotoba-lang/langchain-store` immediately.** Deferred
  per Decision 7 above -- a reasonable near-term follow-up, not a
  rejection.

## Consequences

- `cloud-itonami-isic-8020` promoted from `:blueprint` to
  `:implemented`, with `:maturity :implemented` added to the
  `kotoba-lang/industry` registry entry (no other field changed
  besides what was already correct).
- Establishes the closed four-op/four-action allowlist as a literal,
  structurally-enforced (not merely documented) invariant.
- `test/secsys/governor_self_trip_test.clj` is a dedicated,
  fleet-pattern regression test against the self-tripping scope-
  exclusion bug class -- not just careful wording.
- `MemStore` ‖ `DatomicStore` parity is proven by `test/secsys/
  store_contract_test.clj`.
- The demo (`clojure -M:dev:run`) walks one clean record-log +
  installation-schedule + equipment-supply-coordination + concern-
  flag lifecycle, plus seven HARD-hold scenarios (no-spec-basis,
  unregistered record on two different ops, unconfirmed installation
  permit, an open security concern on two different ops, an
  already-open equipment-supply coordination, a double-schedule, and
  a double supply-coordination), end-to-end.
- `clojure -M:test` / `clojure -M:dev:test`: 44 tests / 405 assertions,
  0 failures, 0 errors. `clojure -M:lint` (clj-kondo): 0 errors, 0
  warnings.

## References

- `cloud-itonami-isic-6511/docs/adr/0001-architecture.md` (origin of
  the general governed-actor architecture pattern)
- `cloud-itonami-isic-4912/docs/adr/0001-architecture.md` (nearest
  structural sibling; mirrored closely, substituting the security-
  systems-services domain for freight-rail)
- `kotoba-lang/langchain-store` (ADR-2607141600; deferred adoption,
  see Decision 7)
- This repo's own pre-existing `blueprint.edn`/`README.md`/`docs/
  business-model.md`/`docs/operator-guide.md` (the blueprint this
  build fills in)
- superproject `com-junkawasaki/root` ADR recording this promotion
  (`90-docs/adr/*-cloud-itonami-isic-8020-security-systems-
  coverage.md`/`.edn`)
