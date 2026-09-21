# Third-Party Dependency Notices

This document summarizes direct third-party dependencies declared by the
LingTong Maven build. It is not a substitute for the license text shipped by
each dependency. Transitive dependencies and licenses must be regenerated and
reviewed for every release.

## Runtime and Provided APIs

| Component | Version | Usage | Declared license |
| --- | --- | --- | --- |
| Java Servlet API (`javax.servlet:javax.servlet-api`) | 4.0.1 | Servlet API | CDDL 1.1 or GPLv2 with Classpath Exception |
| Java WebSocket API (`javax.websocket:javax.websocket-api`) | 1.1 | JSR 356 API | CDDL 1.1 or GPLv2 with Classpath Exception |
| Spring Boot / Autoconfigure | 2.7.18 | Optional Spring Boot integration | Apache License 2.0 |
| Spring WebSocket | 5.3.31 | Optional Spring WebSocket integration | Apache License 2.0 |

## Test Dependencies

| Component | Version | Declared license |
| --- | --- | --- |
| JUnit Jupiter | 5.11.4 | Eclipse Public License 2.0 |
| Jackson Databind | 2.13.5 | Apache License 2.0 |

LingTong does not copy these projects' source into its own source files. Maven
resolves the artifacts under their respective licenses. Release engineering
must inspect the effective dependency tree, generated SBOM, and packaged JARs
before distribution because scopes and transitive dependencies can change.

Tomcat, TongWeb, JBoss, Undertow, Jetty, Netty, Grizzly, and WildFly are not
runtime dependencies of this project. Their names may appear in compatibility
documentation, tests, or dependency-ban rules.
