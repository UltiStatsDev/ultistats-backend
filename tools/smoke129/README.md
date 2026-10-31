# Raw OpenAPI nullable consumer smoke

From the repository root, export the actual MockMvc OpenAPI document and verify
runtime/schema fixtures:

```sh
./gradlew test --tests '*NullableOpenApiContractTest'
pnpm --dir tools/smoke129 install --frozen-lockfile
pnpm --dir tools/smoke129 check
```

The smoke uses openapi-typescript 7.13.0 and TypeScript 6.0.3. It generates from
the complete unmodified `build/contracts/nullable-openapi.json`, then compiles
the generated types and consumer assignments under strict null checking.
Positive and negative type assertions cover optional PATCH fields, explicit
null clearing, required nullable ALWAYS fields, legacy nullable inputs, and
preservation of required nonnullable create properties. Generated files stay
under `build/contracts`.
