#!/usr/bin/env bash

# REPO FIX
exec "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" "$@"
