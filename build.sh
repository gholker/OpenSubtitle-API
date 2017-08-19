#!/usr/bin/env bash

./mvnw clean package
cp target/opensubtitles-1.0-SNAPSHOT-jar-with-dependencies.jar ./fetch-subs.jar
