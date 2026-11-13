#!/bin/bash
# Deploys Firewatch to a host with Docker: rsyncs the sources, builds the image there and (re)starts the stack. Idempotent.
#
#   IGNITE_FIREWATCH_HOST=user@box docker/deploy.sh
#
# Optional: IGNITE_FIREWATCH_DOCKER_CONTEXT, IGNITE_FIREWATCH_UI_PORT, IGNITE_FIREWATCH_TZ, IGNITE_FIREWATCH_REMOTE_DIR,
# IGNITE_FIREWATCH_SSH_DIR (on the host, the ssh key and known_hosts of remote benchmark hosts; docker/ssh by default),
# IGNITE_FIREWATCH_GIT_DIR (on the host, a git repository or bundle for a stand without network; docker/git by default)
# and IGNITE_FIREWATCH_REMOTE_PATH (added to PATH on the host, where a non-interactive ssh shell may not find docker).
# The values of your stand go to docker/deploy.env, which is not committed (see deploy.env.example).
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f docker/deploy.env ]; then
    . docker/deploy.env
fi
HOST=${IGNITE_FIREWATCH_HOST:?set IGNITE_FIREWATCH_HOST=user@host or put it into docker/deploy.env}
CTX=${IGNITE_FIREWATCH_DOCKER_CONTEXT:-default}
DIR=${IGNITE_FIREWATCH_REMOTE_DIR:-ignite-firewatch}
UI_PORT=${IGNITE_FIREWATCH_UI_PORT:-8090}
TIME_ZONE=${IGNITE_FIREWATCH_TZ:-UTC}
SSH_DIR=${IGNITE_FIREWATCH_SSH_DIR:-./ssh}
GIT_SOURCE_DIR=${IGNITE_FIREWATCH_GIT_DIR:-./git}
REMOTE_PATH=${IGNITE_FIREWATCH_REMOTE_PATH:-}

echo "== rsync sources to $HOST:$DIR"
rsync -az --delete --exclude-from=.dockerignore ./ "$HOST:$DIR/"

echo "== build and start on $HOST (docker context $CTX)"
ssh "$HOST" "bash -s" <<REMOTE
set -euo pipefail
export PATH="\$PATH${REMOTE_PATH:+:$REMOTE_PATH}"
D="docker --context $CTX"
\$D volume create ignite-firewatch-data >/dev/null
\$D volume create ignite-firewatch-cache >/dev/null
cd "$DIR/docker"
mkdir -p "$SSH_DIR" "$GIT_SOURCE_DIR"
IGNITE_FIREWATCH_UI_PORT=$UI_PORT IGNITE_FIREWATCH_TZ=$TIME_ZONE IGNITE_FIREWATCH_SSH_DIR=$SSH_DIR \
    IGNITE_FIREWATCH_GIT_DIR=$GIT_SOURCE_DIR \$D compose up -d --build --remove-orphans
\$D compose ps
REMOTE
echo "== UI: http://${HOST#*@}:$UI_PORT"
