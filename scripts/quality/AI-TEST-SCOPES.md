# AI test scopes

`required-tests.txt` is the build job's explicit Surefire selection and report
requirement. Every class must execute at least one test with zero skipped,
failed, or erroneous tests. New AI tests must be classified by
`Verify-Ai-TestInventory.ps1`; a missing class, overlap, or conditional skip in a
required test fails before Maven starts.

`required-service-tests.txt` runs in each disposable MySQL 5.7/8.0 service job:
`ReleaseServicesIT` migrates the empty database and performs the existing
authenticated service smoke; then `AiConfigurationDruidMySqlIT` verifies real
Druid stat/MySQL Wall, empty and filtered list SQL, paging, and malformed HTTP
parameters against that migrated schema. Both reports must exist and pass.
The latter test is read-only and rejects non-CI database targets. The ordinary
build also runs `AiConfigurationDruidHttpTest` against its isolated H2 database
with the real MySQL Wall filter. This is not a full deployed application Druid
acceptance claim.

`native-ai-tests.txt` is an explicit exclusion ledger, not a success or skipped
test report. Those tests require assigned local MySQL/Redis fixtures, private
ledgers, a separate-JVM sequence, or authorized live provider requests. They
are not selected by either mandatory CI job and must be reported separately as
not run unless their actual environment acceptance evidence exists. Do not
enable their environment flags in CI or replace their data with production
credentials. `AiConfigurationDruidNativeQaIT` preserves the former optional
method outside Surefire's default `*Test` discovery; an explicit run without
its assigned flag fails instead of skipping.

Local acceptance evidence cannot replace a remote run for the exact final
candidate. Record the actual commit, Actions URL and every required job result
before Gitflow integration or release.

`AiDistributedRuntimeMySqlIT` is classified in `native-ai-tests.txt`. It requires
assigned local MySQL/Redis and two JVMs; its acceptance evidence must remain
separate. Do not re-run it as a generic CI test. Inclusion in this source
candidate does not assert a new execution of the native test.
