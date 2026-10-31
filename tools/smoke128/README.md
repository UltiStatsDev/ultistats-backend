# Raw event contract regression (#128)

Use Java 21 and the repository Gradle wrapper. Export the actual unmodified
/v3/api-docs document via MockMvc:

    ./gradlew test --tests '*EventSchemaGenerationTest'
    cd tools/smoke128
    pnpm install --frozen-lockfile
    pnpm check

The locked tools generate from the entire raw document with
openapi-typescript 7.13.0, compile with TypeScript 6.0.3 (strict, no skipLibCheck),
and validate all 15 event types against the raw schemas using AJV 2020 and
format validation. No consumer normalization or circular-reference suppression
is applied. Fixtures cover v1 creation/read, supported PATCH variants (including
omitted/null participant corrections), unsupported system PATCH, mandatory v2
eventId and 1–100 batch bounds. These are structural contracts: match roster,
event ordering and other semantic business rules remain controller/service tests.

In coordinated local environments wrap all Gradle invocations with the shared
flock and configured JAVA_HOME/GRADLE_USER_HOME as instructed by the lead.
