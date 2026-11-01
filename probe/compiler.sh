#!/bin/sh
# Checks the sources of the compiler's modules (those :compiler:cli-jvm depends on) with the stdlib, as
# the compiler is built, printing the errors; run from ~/src/kotlin after deps.sh.
P=$(cd "$(dirname "$0")" && pwd)
exec "$P/stdlib.sh" --errors -Xname-based-destructuring=complete -Xcontext-parameters -Xexplicit-backing-fields \
    "$P/../build/probe/deps.jar" "$P/stubs" $(python3 "$P/modules.py" "${@:-:compiler:cli-jvm}")
