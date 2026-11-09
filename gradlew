#!/bin/sh
# Wrapper minimalista de Gradle 8.9
DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
exec java -cp "$DIR/gradle/wrapper/gradle-wrapper.jar:$DIR/gradle/wrapper/gradle-wrapper-shared.jar" org.gradle.wrapper.GradleWrapperMain "$@"
