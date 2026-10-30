#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/jdk-17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="${JAVA_HOME}/bin:$PATH"

echo "=== AlphaPro 索尼相机专业伴侣构建环境 ==="
echo "Java: $(java -version 2>&1 | head -n 1)"
echo "Gradle: $(./gradlew --version 2>&1 | grep 'Gradle ')"

TASK="${1:-assembleDebug}"
echo "Executing task: $TASK ..."
./gradlew "$TASK" --no-daemon
