#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
javac -d "$classes" app/src/main/java/com/hcode91/pocketmicro/{ApkCompatibility,NativeStartupChecks,CrashLogFilter,GlobalLogFile,GameSupportExport}.java tests/{ApkCompatibilityTest,NativeStartupChecksTest,CrashLogFilterTest,GlobalLogFileTest,GameSupportExportTest}.java
java -cp "$classes" com.hcode91.pocketmicro.ApkCompatibilityTest
java -cp "$classes" com.hcode91.pocketmicro.NativeStartupChecksTest
java -cp "$classes" com.hcode91.pocketmicro.CrashLogFilterTest

java -cp "$classes" com.hcode91.pocketmicro.GlobalLogFileTest
java -cp "$classes" com.hcode91.pocketmicro.GameSupportExportTest
