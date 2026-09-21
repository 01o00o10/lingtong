# Contributing to LingTong

Thank you for helping improve LingTong. The project is currently an early
runtime implementation, so protocol correctness, bounded resource use, and
behavioral compatibility take priority over feature count.

## Before Starting

Open an issue before a large change. Security vulnerabilities must follow
[SECURITY.md](SECURITY.md), not the public issue tracker.

All external contributions require acceptance of [CLA.md](CLA.md). The CLA
allows the project owner to continue offering both AGPL and commercial
licenses. Do not submit employer-owned or third-party code without permission.

## Build

Requirements:

- JDK 8 or later; compatibility changes must be verified on JDK 8.
- Maven 3.8 or later.

```shell
mvn -B -ntp -DskipTests clean package
```

The public repository contains production source and buildable examples but
does not publish the project's test suites or design documents. Contributors
should provide reproducible steps and a verification plan with each behavior
change. Maintainers add or update private tests and run the private unit,
integration, compatibility, and security suites before merging or releasing.

## Change Requirements

- Keep changes focused and preserve module ownership boundaries.
- Provide reproducible verification steps for behavior changes and protocol
  edge cases.
- Do not introduce Netty, Tomcat, Jetty, Undertow, Grizzly, or another server
  implementation as a runtime dependency.
- Keep Java 8 source and bytecode compatibility unless a versioned roadmap
  explicitly changes the baseline.
- Do not commit credentials, customer data, generated `target` directories, or
  production certificates.
- Document user-visible behavior and compatibility limitations.

## Pull Requests

A pull request should explain the problem, behavior before and after, test
commands, compatibility impact, and any security or performance implications.
By submitting a pull request and completing the CLA check, You confirm that You
have the right to contribute the change under the CLA.
