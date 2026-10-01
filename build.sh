#!/usr/bin/env bash
# Build SourceMovement.jar, run the physics tests, and (with --deploy) copy the mod into ~/Zomboid/mods.
set -euo pipefail
cd "$(dirname "$0")"

GAME="${PZ_GAME:-C:/Program Files (x86)/Steam/steamapps/common/ProjectZomboid}"
JDK="${JDK_BIN:-C:/Program Files/Java/jdk-25/bin}"
MOD=mod/ViewpointSourceMovement
DEPLOY_DIR="${PZ_MODS:-$USERPROFILE/Zomboid/mods}"
VIEWPOINT_JAR="${VIEWPOINT_JAR:-C:/Program Files (x86)/Steam/steamapps/workshop/content/108600/3809306528/mods/Viewpoint/42/media/java/client/Viewpoint.jar}"

rm -rf build
mkdir -p build/classes build/test "$MOD/42/media/java" "$MOD/common"

"$JDK/javac" --release 17 -Xlint:all -Xlint:-options -Xlint:-classfile \
    -cp "$GAME/projectzomboid.jar;$GAME/ZombieBuddy.jar" \
    -d build/classes src/sourcemove/*.java

"$JDK/javac" --release 17 -d build/test src/sourcemove/Physics.java test/sourcemove/PhysicsTest.java
"$JDK/java" -cp build/test sourcemove.PhysicsTest

# Weave the @Patch classes into the real game classes offline and run the JVM verifier on the result.
CP="$GAME/ZombieBuddy.jar;$GAME/projectzomboid.jar;build/classes"
mkdir -p build/tools
"$JDK/javac" -cp "$CP" -d build/tools tools/WeaveCheck.java
PATCHES=$(cd build/classes && ls sourcemove/Patch_*.class | sed 's#/#.#; s#\.class$##')
"$JDK/java" -cp "$CP;build/tools" WeaveCheck "$GAME/projectzomboid.jar" "$GAME/ZombieBuddy.jar" build/classes "--jar=$VIEWPOINT_JAR" $PATCHES

"$JDK/jar" --create --file "$MOD/42/media/java/SourceMovement.jar" -C build/classes .
echo "built $MOD/42/media/java/SourceMovement.jar"

if [[ "${1:-}" == "--deploy" ]]; then
    rm -rf "$DEPLOY_DIR/ViewpointSourceMovement"
    cp -r "$MOD" "$DEPLOY_DIR/"
    echo "deployed to $DEPLOY_DIR/ViewpointSourceMovement"
fi
