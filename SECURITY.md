# Security Policy

## Supported Versions

Until the first stable release, only the latest commit on `main` and the most
recent pre-release receive security fixes. No production security support is
promised for `0.x` builds unless a separate commercial agreement says so.

## Reporting a Vulnerability

Do not open a public issue for an undisclosed vulnerability.

Use GitHub Private Vulnerability Reporting from the repository Security tab.
Include the affected version, configuration, reproduction steps, impact, and
any suggested mitigation. If private reporting is unavailable, contact the
owner through [the GitHub profile](https://github.com/01o00o10) and request a
private reporting channel without including exploit details in public.

The project aims to acknowledge a complete report within five business days.
Response and remediation timelines depend on severity and available maintainer
capacity. Please allow a coordinated disclosure period before publication.

## Scope Notes

Particularly useful reports cover HTTP parsing or request smuggling, HTTP/2
state handling, WebSocket framing, TLS configuration, WAR extraction and class
loading, path traversal, authentication, Session handling, resource
exhaustion, or disclosure of secrets through diagnostics.

The public repository intentionally excludes the private test suites and their
fixtures. Do not attach private keys, access tokens, customer data, or complete
unredacted diagnostic logs to public issues.
