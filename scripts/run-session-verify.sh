#!/usr/bin/env bash
# Full verification suite for this session's gate ledgers: unit tests, lint,
# assembly, server tests, repository static checks, and the Node test suites
# CI runs. Prints SESSION-VERIFY-OK only when every step passes.
set -euo pipefail
cd "$(dirname "$0")/.."

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleRelease :server:api:test :server:api:installDist

yamllint .
find . -name '*.sh' -not -path './.git/*' -not -path './*/build/*' -print0 |
  xargs -0 -n1 shellcheck

node scripts/check-adrs.mjs
node scripts/check-secrets.mjs
node --test scripts/test-deploy.mjs scripts/test-admin-ui.mjs
node --test scripts/test-provisioning.mjs
node scripts/verify-audio-route.mjs
node scripts/verify-call-ui-routes.mjs
node scripts/verify-read-receipts.mjs
node scripts/verify-typing.mjs
node scripts/verify-reactions.mjs
node scripts/verify-reply.mjs
node scripts/verify-deletion.mjs
node scripts/verify-voice.mjs
node scripts/verify-video-calls.mjs
node scripts/verify-last-seen.mjs
node scripts/verify-quick-reply.mjs
node scripts/verify-previews.mjs
node scripts/verify-screenshots.mjs

echo "SESSION-VERIFY-OK"
