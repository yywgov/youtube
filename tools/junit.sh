#!/usr/bin/env bash
# Local JVM unit tests (438) without Gradle: plain-Java classes + app/src/test, against android.jar stubs.
# Usage: tools/junit.sh /path/to/android.jar      (CI runs the same tests with ./gradlew testReleaseUnitTest)
# The plain-Java sources (no Android runtime) are the ones the tests cover; add a new one to SRC below.
set -euo pipefail
ANDROID_JAR="${1:-${ANDROID_JAR:-}}"
[ -f "$ANDROID_JAR" ] || { echo "usage: tools/junit.sh path/to/android.jar"; exit 1; }
cd "$(git rev-parse --show-toplevel)"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/yiwei-junit"
mkdir -p "$CACHE"
[ -s "$CACHE/junit.jar" ] || curl -sSfL -o "$CACHE/junit.jar" https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar
[ -s "$CACHE/hamcrest.jar" ] || curl -sSfL -o "$CACHE/hamcrest.jar" https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar
P=app/src/main/java/com/google/android/youtube/pro
SRC="$P/PrefsModel.java $P/Logic.java $P/Prefs.java $P/Diag.java $P/Bg.java $P/KeyRenameStream.java"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
CP="$ANDROID_JAR:$CACHE/junit.jar:$CACHE/hamcrest.jar"
javac -nowarn --release 11 -d "$OUT" -cp "$CP" $SRC $(find app/src/test -name '*.java')
TESTS=$(cd app/src/test/java && find . -name '*Test.java' | sed 's#^\./##;s#\.java$##;s#/#.#g')
java -cp "$OUT:$CP" org.junit.runner.JUnitCore $TESTS
