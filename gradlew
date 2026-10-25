#!/bin/sh
#
# Gradle start-up script for POSIX (Linux/macOS/CI).
# Simplified POSIX-only wrapper. The canonical script also handles
# Cygwin/MSYS; gradlew.bat is not included in this repo (builds run
# on Linux CI or Android Studio, which generates its own wrapper).
#
# The wrapper jar (gradle/wrapper/gradle-wrapper.jar) and properties
# (gradle/wrapper/gradle-wrapper.properties) were placed by Phase 3
# from the Gradle 8.7 distribution because `gradle wrapper` itself
# cannot run in this sandbox (Gradle daemon loopback TCP is blocked
# there — see docs/apk-build-phase3.md). The files are the standard
# Gradle 8.7 wrapper files; on a normal machine they behave exactly
# like a generated wrapper.

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")

# Resolve the location of this script, following symlinks.
PRG="$0"
while [ -h "$PRG" ]; do
    ls=$(ls -ld "$PRG")
    link=$(expr "$ls" : '.*-> \(.*\)$')
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=$(dirname "$PRG")"/$link"
    fi
done
SAVED=$(pwd)
cd "$(dirname "$PRG")" >/dev/null || exit 1
APP_HOME=$(pwd -P)
cd "$SAVED" >/dev/null || exit 1

DEFAULT_JVM_OPTS="-Xmx64m -Xms64m"

# Use JAVA_HOME if set, else fall back to `java` on PATH.
if [ -n "$JAVA_HOME" ]; then
    if [ -x "$JAVA_HOME/bin/java" ]; then
        JAVACMD="$JAVA_HOME/bin/java"
    else
        echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2
        exit 1
    fi
else
    JAVACMD="java"
    if ! command -v java >/dev/null 2>&1; then
        echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH." >&2
        exit 1
    fi
fi

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# shellcheck disable=SC2086 (intentional word-splitting of JVM opts)
exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
