#!/bin/sh

EXIT_CODE=0

JVM_PROJECT="."

echo "Running formatting checks..."
echo "Checking JVM files..."
JVM_CHANGED=$(git diff --cached --name-only --relative | grep -E '\.(kt|kts|java)$')
echo "JVM files changed: $JVM_CHANGED"
if [ -n "$JVM_CHANGED" ]; then
    cd $JVM_PROJECT || exit
    echo "Running JVM formatting checks..."
    rm -rf .gradle/configuration-cache
    ./gradlew --no-daemon spotlessCheck
    GRADLE_EXIT_CODE=$?
    if [ $GRADLE_EXIT_CODE -ne 0 ]; then
        EXIT_CODE=$GRADLE_EXIT_CODE
    fi
    cd - || exit
fi

exit $EXIT_CODE
