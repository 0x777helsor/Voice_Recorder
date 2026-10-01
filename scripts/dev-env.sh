#!/usr/bin/env bash
# Local toolchain bootstrap for this development container.
#
# NOT part of the project. The repository does not depend on this file; it exists
# because the build machine has no system JDK or Android SDK. A normal developer
# workstation already has both and should ignore this entirely.
#
# Usage:  source scripts/dev-env.sh
#
# See docs/TOOLCHAIN.md for why these versions and this layout.
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/tmp/opencode/toolchain/jdk-21.0.12.1+1}"
export ANDROID_HOME="${ANDROID_HOME:-/tmp/opencode/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# Gradle's dependency cache lives on the main disk, not the tmpfs, because the
# Android build cache alone is >1 GB.
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle-safesignal}"

# Constrained build machine: keep the daemon small and single-threaded.
export GRADLE_OPTS="${GRADLE_OPTS:--Dorg.gradle.daemon=true -Dorg.gradle.workers.max=2}"

export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_HOME=$ANDROID_HOME"
echo "GRADLE_USER_HOME=$GRADLE_USER_HOME"
java -version 2>&1 | head -1
