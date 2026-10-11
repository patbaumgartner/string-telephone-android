#!/usr/bin/env node
/**
 * Gate support: verify the server Dockerfile builds on JDK 27 and runs on a
 * JDK 27 runtime. Part of the docs-cleanup + JDK 27 upgrade ledger.
 */
import { readFileSync } from 'node:fs';

const text = readFileSync('server/api/Dockerfile', 'utf8');

const buildStage = /FROM\s+gradle:(\S+)-jdk(\d+)\s+AS\s+build/.exec(text);
if (!buildStage) {
  console.error('DOCKERFILE-JDK-FAIL: no gradle build stage found');
  process.exit(1);
}
if (Number(buildStage[2]) < 27) {
  console.error(`DOCKERFILE-JDK-FAIL: build stage uses JDK ${buildStage[2]}, expected >= 27`);
  process.exit(1);
}

const runtime = /FROM\s+eclipse-temurin:(\d+)-jre/.exec(text);
if (!runtime) {
  console.error('DOCKERFILE-JDK-FAIL: runtime image is not eclipse-temurin:<v>-jre');
  process.exit(1);
}
if (Number(runtime[1]) < 27) {
  console.error(`DOCKERFILE-JDK-FAIL: runtime JRE is ${runtime[1]}, expected >= 27`);
  process.exit(1);
}

console.log('DOCKERFILE-JDK-OK');
