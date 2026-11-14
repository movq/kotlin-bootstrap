#!/bin/sh
# Compiles the Kotlin sources of the compiler's modules (those :compiler:cli-jvm depends on) to
# build/probe/classes, against the stdlib's sources, as compiler.sh checks them; run from ~/src/kotlin
# after deps.sh. With ERRORS=1, prints the errors.
P=$(cd "$(dirname "$0")" && pwd)
S=~/src/kotlin/libraries/stdlib
I=~/src/kotlinx.collections.immutable/core
OUT=$P/../build/probe/classes
rm -rf "$OUT" && mkdir -p "$OUT"
CP="-Xname-based-destructuring=only-syntax"
for r in common/src src unsigned/src jvm/src jvm/runtime jvm/builtins jdk7/src jdk8/src; do CP="$CP -cp $S/$r"; done
exec java -Xss64m -Xmx8g -jar "$P/../build/kbc.jar" compile -d "$OUT" --module-name kotlin-compiler --keep-going ${ERRORS:+--errors} \
    $CP -cp "$I/commonMain/src" -cp "$I/jvmMain/src" -cp "$P/../build/probe/deps.jar" -cp "$P/stubs" \
    $(python3 "$P/modules.py" "${@:-:compiler:cli-jvm}")
