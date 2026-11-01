#!/bin/sh
# Builds build/probe/deps.jar: the third-party classes of kotlinc's jar (with its protobuf, not the
# Kotlin compiler's own), and annotations-13.0, as the class path for checking the compiler's sources.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
LIB=$ROOT/tools/kotlinc-2.4.21/lib
OUT=$ROOT/build/probe
rm -rf "$OUT/deps" && mkdir -p "$OUT/deps"
cd "$OUT/deps"
unzip -q -o "$LIB/kotlin-compiler.jar" 'com/*' 'it/*' 'io/*' 'net/*' 'javax/*' 'kotlinx/*' 'one/*' 'org/*'
mv org/jetbrains/kotlin/protobuf protobuf.tmp && rm -rf org/jetbrains/kotlin
mkdir -p org/jetbrains/kotlin && mv protobuf.tmp org/jetbrains/kotlin/protobuf
unzip -q -o "$LIB/annotations-13.0.jar" 'org/*'
jar --create --file "$OUT/deps.jar" .
cd .. && rm -rf deps
