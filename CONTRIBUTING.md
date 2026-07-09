# Contributing

`cloud-itonami-8020` accepts contributions to the OSS blueprint, capability
bindings, policy tests, documentation and operator model.

## Development
The capability layer lives in `kotoba-lang/robotics` and
`kotoba-lang/labor`. This repo holds the business blueprint and
operator contracts.

```bash
clojure -X:test
clojure -M:lint
```

## Rules
- Do not commit real client or premises data.
- Keep robot dispatch and follow-up records behind the Security
  Systems Governor.
- Treat dispatch/installation workflows as high-risk: add tests for
  robot-safety gating, licensing/response-time scope, evidence,
  disclosure and audit logging.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether operator or certification docs need
updates.
