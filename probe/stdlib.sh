#!/bin/sh
# Runs kbc check over the given arguments, then the stdlib sources, as built; run from ~/src/kotlin.
S=~/src/kotlin/libraries/stdlib
exec java -Xss64m -jar "$(dirname "$0")/../build/kbc.jar" check "$@" -Xname-based-destructuring=only-syntax \
    $S/common/src $S/src $S/unsigned/src $S/jvm/src $S/jvm/runtime $S/jvm/builtins $S/jdk7/src $S/jdk8/src
