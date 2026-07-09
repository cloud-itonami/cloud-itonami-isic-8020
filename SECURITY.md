# Security Policy

This project handles licensing-scope, response-time-scope and
follow-up-record workflows. Treat vulnerabilities as potentially high
impact even when the demo data is synthetic.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- licensing/response-time credential exposure
- real client or premises data exposure
- authorization bypass
- Security Systems Governor bypass
- audit-ledger tampering
- over-disclosure in follow-up records or exports
- tenant isolation failures

## Reporting

Use GitHub private vulnerability reporting when available for the repository.
If that is unavailable, contact the repository maintainers through the
cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on client/premises data, policy enforcement or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real client and premises data outside this repository.
- Run policy tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for operators and service accounts.
