#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
javac -d "$classes" app/src/main/java/io/github/hozdogus/pocketouya/{ApkCompatibility,NativeStartupChecks,CrashLogFilter}.java tests/{ApkCompatibilityTest,NativeStartupChecksTest,CrashLogFilterTest}.java
java -cp "$classes" io.github.hozdogus.pocketouya.ApkCompatibilityTest
java -cp "$classes" io.github.hozdogus.pocketouya.NativeStartupChecksTest
java -cp "$classes" io.github.hozdogus.pocketouya.CrashLogFilterTest
