# Automated test results

## Unit tests

```
./gradlew.bat testDebugUnitTest   -> BUILD SUCCESSFUL (UP-TO-DATE)
```

Parsed from `app/build/test-results/testDebugUnitTest/*.xml` rather than trusting the task status:

| Metric | Value |
|---|---|
| Test classes | 94 |
| Tests | 781 |
| Failures | 0 |
| Errors | 0 |
| Skipped | 7 |

Caveat: the task was UP-TO-DATE, meaning Gradle considered inputs unchanged since the 14:22 run.
The working tree has not changed since 05:18 and the build is up-to-date, so the results correspond
to the current sources - but this is a Gradle-input argument, not a fresh execution.

## JVM parser harness (re-run by this engagement)

`harness/QaParserHarness.java` compiled against the current `app/build/tmp/kotlin-classes/debug` and
run over all 52 fixtures:

```
PASS=52 FAIL=0
```

Output: `evidence/logcat/harness-rerun.json`. This reproduces the previous engagement's claim
independently and confirms it was not fabricated.

Known limitation: the harness mangles non-ASCII in its own stdout (`detected_title_headers` shows
`????`), a console-encoding artefact of the harness, not the parser - the app UI renders the same
Japanese headers correctly.

## Instrumentation tests

`androidTest` exists (`androidx.test` runner configured, `app-debug-androidTest.apk` built) but no
instrumentation test classes were found to run. Not executed.

## Static/structural

`scripts/deepseek_structure_check.py`: 49/52 fixtures fully clean; the 3 exceptions are the
deliberately corrupt archives.
