#!/bin/bash
# Runs Firewatch as a background process of this machine, without a container. Needs JDK 17 and git: the distribution
# (libs/ next to bin/) brings Maven in maven/; in the repository it takes mvn from the PATH and builds missing jars.
#
#   bin/firewatch.sh start|stop|restart|status|logs
#
# Settings are in config/ignite-firewatch.properties (IGNITE_FIREWATCH_CONFIG names another file); an environment
# variable IGNITE_FIREWATCH_<KEY> overrides a line. One instance is one stand: a second stand is a second file with its
# own data.dir, cache.dir and port. The pid and the log live in data.dir.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
CONFIG=${IGNITE_FIREWATCH_CONFIG:-config/ignite-firewatch.properties}
[ -n "${IGNITE_FIREWATCH_CONFIG:-}" ] && [ ! -f "$CONFIG" ] && { echo "no config file $CONFIG" >&2; exit 1; }

# a line of the config file: "key = value", the last one wins, comments and blanks are skipped
setting() {
    [ -f "$CONFIG" ] || return 0
    sed -n "s/^[[:space:]]*$1[[:space:]]*[=:][[:space:]]*//p" "$CONFIG" | tail -1 | sed 's/[[:space:]]*$//'
}
DATA_DIR=${IGNITE_FIREWATCH_DATA_DIR:-$(setting data.dir)}
DATA_DIR=${DATA_DIR:-data}
CACHE_DIR=${IGNITE_FIREWATCH_CACHE_DIR:-$(setting cache.dir)}
CACHE_DIR=${CACHE_DIR:-cache}
PORT=${IGNITE_FIREWATCH_PORT:-$(setting port)}
PORT=${PORT:-8090}
if [ -f libs/controller.jar ]; then
    JAR=libs/controller.jar
    BUILT=libs/harness.jar
    OWN_MVN=$ROOT/maven/bin/mvn
else
    JAR=modules/controller/target/controller.jar
    BUILT=modules/harness/target/harness.jar
    OWN_MVN=
fi
HARNESS_JAR=${IGNITE_FIREWATCH_HARNESS_JAR:-$(setting harness.jar)}
HARNESS_JAR=${HARNESS_JAR:-$BUILT}
HOST=${IGNITE_FIREWATCH_BIND_HOST:-$(setting bind.host)}
HOST=${HOST:-127.0.0.1}
[ "$HOST" != 0.0.0.0 ] || HOST=127.0.0.1
JAVA=${IGNITE_FIREWATCH_JAVA:-$(setting java)}
JAVA=${JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/}java}
MVN=${IGNITE_FIREWATCH_MVN:-$(setting mvn)}
[ -n "$OWN_MVN" ] && [ -x "$OWN_MVN" ] || OWN_MVN=mvn
MVN=${MVN:-$OWN_MVN}
PID_FILE=$DATA_DIR/firewatch.pid
LOG=$DATA_DIR/firewatch.log
LOG_LIMIT_BYTES=10485760
WAIT_SEC=30

running() {
    [ -f "$PID_FILE" ] || return 1
    case "$(ps -p "$(cat "$PID_FILE")" -o command= 2>/dev/null || true)" in
        *controller.jar*) return 0 ;;
        *) return 1 ;;
    esac
}

need() {
    command -v "$1" >/dev/null 2>&1 || { echo "$1 is not found; $2" >&2; exit 1; }
}

check_tools() {
    need "$JAVA" "set JAVA_HOME or IGNITE_FIREWATCH_JAVA"
    need "$MVN" "install Maven or set IGNITE_FIREWATCH_MVN"
    need git "install git"
    local major
    major=$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
    [ "${major:-0}" -ge 17 ] || { echo "JDK 17 or newer is needed, $JAVA is ${major:-unknown}" >&2; exit 1; }
}

port_open() {
    (exec 3<>"/dev/tcp/$HOST/$PORT") 2>/dev/null
}

start() {
    if running; then
        echo "already running, pid $(cat "$PID_FILE")"
        return 0
    fi
    if port_open; then
        echo "port $PORT is already in use" >&2
        return 1
    fi
    check_tools
    if [ ! -f "$JAR" ] || [ ! -f "$HARNESS_JAR" ]; then
        [ -f pom.xml ] || { echo "$JAR or $HARNESS_JAR is missing" >&2; return 1; }
        echo "building the jars"
        "$MVN" -q -DskipTests package
    fi
    mkdir -p "$DATA_DIR" "$CACHE_DIR"
    if [ -f "$LOG" ] && [ "$(wc -c < "$LOG")" -gt "$LOG_LIMIT_BYTES" ]; then
        mv "$LOG" "$LOG.1"
    fi
    nohup "$JAVA" -Xmx128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -jar "$JAR" </dev/null >>"$LOG" 2>&1 &
    echo $! > "$PID_FILE"
    for _ in $(seq 1 "$WAIT_SEC"); do
        running || break
        if port_open; then
            echo "running, pid $(cat "$PID_FILE"), http://$HOST:$PORT"
            return 0
        fi
        sleep 1
    done
    echo "did not start, the end of $LOG:" >&2
    tail -n 20 "$LOG" >&2
    if running; then
        kill -TERM "$(cat "$PID_FILE")"
    fi
    rm -f "$PID_FILE"
    return 1
}

stop() {
    if ! running; then
        rm -f "$PID_FILE"
        echo "not running"
        return 0
    fi
    local pid
    pid=$(cat "$PID_FILE")
    kill -TERM "$pid"
    for _ in $(seq 1 "$WAIT_SEC"); do
        if ! running; then
            rm -f "$PID_FILE"
            echo "stopped"
            return 0
        fi
        sleep 1
    done
    echo "still running after $WAIT_SEC s, killing pid $pid"
    kill -KILL "$pid"
    rm -f "$PID_FILE"
}

status() {
    if running; then
        echo "running, pid $(cat "$PID_FILE"), http://$HOST:$PORT, data in $DATA_DIR"
    else
        echo "stopped"
        return 3
    fi
}

case "${1:-}" in
    start) start ;;
    stop) stop ;;
    restart) stop; start ;;
    status) status ;;
    logs)
        [ -f "$LOG" ] || { echo "no log yet: $LOG" >&2; exit 1; }
        tail -n 100 -f "$LOG"
        ;;
    *)
        echo "usage: $0 start|stop|restart|status|logs" >&2
        exit 2
        ;;
esac
