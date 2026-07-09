# cloud-itonami-8020

Open Business Blueprint for **ISIC Rev.5 8020**: security systems
service activities (alarm system installation and monitoring, CCTV
installation and monitoring, and access-control system installation
and maintenance).

This repository designs a forkable OSS business for community
security-systems service operations: technician dispatch and
verified-response scope management, robotics-assisted installation/
maintenance-mission support, and monitoring/reconciliation records —
run by a qualified operator so an alarm/CCTV/access-control company
keeps its own licensing and response history instead of renting a
closed security-systems platform.

## Scope note: electronic systems, not guard labor

`cloud-itonami-isic-8010` ("Community Private Security Operations")
already covers PERSONNEL-based security: guards and patrols deployed
as the security measure itself. This repository is deliberately
scoped to the SEPARATE business of ELECTRONIC security systems --
installing, maintaining and monitoring alarms, CCTV and access-control
equipment, where the security function is performed by the system
itself rather than by guard presence. This is a distinctly licensed
activity in every jurisdiction checked: California's Bureau of
Security and Investigative Services issues a separate "Alarm Company
Operator" license distinct from its "Private Patrol Operator" license;
the UK's Security Industry Authority licenses "Public Space
Surveillance (CCTV)" separately from "Door Supervisor"/"Security
Guarding"; Japan's 警備業法 (Security Business Act) treats 機械警備業務
(mechanical/systems security) as its own registration category
(機械警備業務開始届出書) with its own operational requirements (e.g. a
statutory maximum response-time rule for monitored alarms) distinct
from ordinary guarding under the same law. `cloud-itonami-isic-8010`'s
own `blueprint.edn` and docs contain no mention of alarms, CCTV or
access-control systems -- confirmed non-redundant before selection.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (installation-
support drones/rigs, camera-alignment and cable-routing assist,
periodic system-health inspection) operate under an actor that
proposes actions and an independent **Security Systems Governor**
that gates them. The governor never dispatches a monitoring alert
response or installation job itself; `:high`/`:safety-critical`
actions (a monitored-alarm response dispatched outside the verified
response-time scope, an installation job without a completed permit/
compliance check, a monitoring-center escalation without verified
evidence) require human sign-off.

## Core Contract

```text
intake + identity + licensing/response scope + technician registration
        |
        v
Security Systems Advisor -> Security Systems Governor -> match, dispatch, follow-up record, or human approval
        |
        v
robot actions (gated) + installation/monitoring record + audit ledger
```

No automated advice can dispatch an alarm-response, installation or
monitoring action the governor refuses, match an unregistered
technician to a job, or publish a follow-up record without governor
approval and audit evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `8020`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs
- [`kotoba-lang/labor`](https://github.com/kotoba-lang/labor) — technician registration, dispatch, timesheet/follow-up contracts

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
