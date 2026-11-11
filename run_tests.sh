#!/bin/bash
# New Medicine — unit tests (pure core, fake clock, no Android).
# Compiles core main + core test with kotlinc 2.0.20 and runs EVERY test
# class explicitly via JUnitCore. Every class must be named here — a class
# missing from this list is silently skipped (v1.9 lesson).
set -e
cd "$(dirname "$0")"
export JAVA_HOME=~/workspace/.jdk
export PATH="$JAVA_HOME/bin:$PATH"
KCP=$(cat .kotlinc-cp.txt)
STDLIB="/home/hatch/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.20/7388d355f7cceb002cd387ccb7ab3850e4e0a07f/kotlin-stdlib-2.0.20.jar"
JUNIT=$(find ~/.gradle/caches -name "junit-4.13.2.jar" | head -1)
HAMCREST=$(find ~/.gradle/caches -name "hamcrest-core-1.3.jar" | head -1)
echo "junit: $JUNIT"
echo "hamcrest: $HAMCREST"
rm -rf out/core-main out/core-test
mkdir -p out/core-main out/core-test
"$JAVA_HOME/bin/java" -Xmx1g -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -cp "$STDLIB" -jvm-target 17 -d out/core-main \
  $(find core/src/main/kotlin -name "*.kt") 2>&1 | grep -v "^warning:" || true
"$JAVA_HOME/bin/java" -Xmx1g -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -cp "$STDLIB:out/core-main:$JUNIT:$HAMCREST" -jvm-target 17 \
  -d out/core-test $(find core/src/test/kotlin -name "*.kt") 2>&1 | grep -v "^warning:" || true
"$JAVA_HOME/bin/java" -cp "out/core-test:out/core-main:$STDLIB:$JUNIT:$HAMCREST" \
  org.junit.runner.JUnitCore \
  com.moon.newmedicine.core.AuditRegressionTest \
  com.moon.newmedicine.core.CallDeferTest \
  com.moon.newmedicine.core.CatchupTest \
  com.moon.newmedicine.core.ChainingTest \
  com.moon.newmedicine.core.ConfirmGuardTest \
  com.moon.newmedicine.core.DecideWakePromptTest \
  com.moon.newmedicine.core.DisplayNumberTest \
  com.moon.newmedicine.core.DropCountTest \
  com.moon.newmedicine.core.HomeViewTest \
  com.moon.newmedicine.core.MigrationIsolationTest \
  com.moon.newmedicine.core.NagSkipTest \
  com.moon.newmedicine.core.NewMedicineConfigTest \
  com.moon.newmedicine.core.NotificationSoundTest \
  com.moon.newmedicine.core.OverlapGuardTest \
  com.moon.newmedicine.core.ReminderSoundTest \
  com.moon.newmedicine.core.RepairTest \
  com.moon.newmedicine.core.RingBehaviorTest \
  com.moon.newmedicine.core.RingStartFallbackTest \
  com.moon.newmedicine.core.ScheduleEngineTest \
  com.moon.newmedicine.core.SessionCancelTest \
  com.moon.newmedicine.core.SessionStartTimeTest \
  com.moon.newmedicine.core.SilenceTriggerTest \
  com.moon.newmedicine.core.SnoozeDeferCutoffTest \
  com.moon.newmedicine.core.SnoozeExpiryRingTest \
  com.moon.newmedicine.core.SnoozeWatchdogTest \
  com.moon.newmedicine.core.SpecialPositionTest \
  com.moon.newmedicine.core.WakeChainSelfHealTest \
  com.moon.newmedicine.core.WakeDetectorTest \
  com.moon.newmedicine.core.update.UpdateCheckerTest \
  com.moon.newmedicine.core.update.UpdateIntegrityTest \
  com.moon.newmedicine.core.update.UpdateProgressTest
