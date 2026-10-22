# givemeticon

Korean gifticon marketplace backend. Spring Boot app in `src/`, Gradle build at the repo root. First-come coupon issuance is the area under active study; everything else is maintained, not extended.

## Working style

* State your assumptions before coding. If the task has more than one reasonable reading, say so and take the narrowest one unless told otherwise.
* Write the minimum code for the stated requirement. No unrequested features, no single-use abstractions, no speculative configuration.
* Touch only what the task names. Match the surrounding style. Mention unrelated dead code; do not delete it.
* Turn every task into a verifiable goal. For a bug, write the reproducing test before the fix.
* Prefer modifying an existing class over adding a new layer when both are correct.

## Repository map

* `src/main/java/com/jinddung2/givemeticon/` — Java 21 (Temurin), Spring Boot 3.3, MyBatis (XML mappers in `src/main/resources/mapper/`), Flyway (`src/main/resources/db/migration/`), MySQL, Redis x2 (mail, coupon), Kafka, RabbitMQ.
* `src/test/` — unit tests, plus `@Tag("integration")` tests that need local infra.
* `givemeticon-config/` — private config submodule. Its `*.yml` files are copied into resources at build time. Never edit it, never print its values, never commit copies of it.
* `docs/` — ADRs, decision records, experiment results. Historical evidence; update only when the task asks.
* `scripts/` — load-test and verification scripts for coupon flows. Do not run load tests unless the task asks.
* `docker-compose.infra.yml` — local MySQL/Redis/Kafka. `docker-compose.coupon-mq.yml` — RabbitMQ for coupon MQ.
* `codestyle/` — Naver checkstyle and IntelliJ formatter rules. Follow them; do not reformat files you did not change.

## Commands (from repo root)

* Compile: `./gradlew compileJava compileTestJava`
* One test class: `./gradlew test --tests 'com.jinddung2.givemeticon.domain.coupon.*' -x jacocoTestReport`
* Unit tests: `./gradlew test` (excludes `@Tag("integration")`, runs jacoco)
* Integration tests: `./gradlew integrationTest` — only when the task asks, and only after confirming the infra containers are up (`docker ps`). They use the datasource in `givemeticon-config/application-local.yml`; never point them at a database you did not start for this task.

## Hard rules

* Branches: `<type>/<short-kebab-topic>` with type in `feat | fix | refactor | test | docs | chore`. Never a `claude/` prefix.
* Never edit, push, or merge `develop` or `main`. Never amend, rebase, reset, or force-push commits you did not create in this task.
* Never start, stop, or remove Docker containers you did not create in this task. If infra is down, report it and stop.
* Never change lock, transaction, ACK, or coupon-admission contracts unless the task explicitly asks. If a change seems necessary, stop and report why.
* Never commit credentials, tokens, `.env`, or raw CLI transcripts.
* A check you did not run is INCOMPLETE, never PASS. Infra failures (connection refused, container missing) are environment problems, not test failures: classify them separately.

## Final report (every task)

1. Branch, base SHA, commit SHAs with the files each one changed
2. Every command you ran for verification and its result line (pass/fail/skip counts)
3. Checks you could not run and why
4. Reason for each change
5. Remaining risk
