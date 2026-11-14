#!/usr/bin/env bash
# Explicit `sh script.sh` invocations ignore the shebang. Switch to Bash before
# evaluating Bash-specific options, substitutions, or arrays below.
if [ -z "${BASH_VERSION:-}" ]; then
    exec bash "$0" "$@"
fi
set -Eeo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname -- "$SCRIPT_DIR")"
LOCAL_DIR="$REPO_DIR/.local"
LOG_DIR="$LOCAL_DIR/logs"
PID_DIR="$LOCAL_DIR/pids"
TOOLS_DIR="$REPO_DIR/tools"
[[ -d "$TOOLS_DIR" ]] || TOOLS_DIR="$SCRIPT_DIR/tools"

ACTION="start"
START_FRONTEND=0
ENABLE_LEGACY_ALERTS=0
REQUESTED_SERVICES=()

usage() {
    cat <<'EOF'
Usage:
  ./run-all-services.sh [start|stop|restart] [service ...] [options]

Options:
  --frontend          Include the Ecom-frontend sibling project
  --legacy-alerts     Include Ecom-Alerts (requires Kafka)
  -h, --help          Show this help

Before start/restart, processes listening on selected service ports are stopped.
With no service names, starts/stops all core backend services. Service names
may be specified exactly; use "all" or "*" for all selected backend services.
Examples:
  ./run-all-services.sh start
  ./run-all-services.sh start --frontend
  ./run-all-services.sh start --legacy-alerts
  ./run-all-services.sh restart catalog-service
  ./run-all-services.sh stop frontend
EOF
}

if (($#)) && [[ "$1" != -* ]]; then
    first_argument="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
    case "$first_argument" in
        start|stop|restart) ACTION="$first_argument"; shift ;;
    esac
fi

while (($#)); do
    case "$1" in
        --frontend) START_FRONTEND=1 ;;
        --legacy-alerts) ENABLE_LEGACY_ALERTS=1 ;;
        -h|--help) usage; exit 0 ;;
        *) REQUESTED_SERVICES+=("$1") ;;
    esac
    shift
done

import_env_file() {
    local file="$1" line key value
    [[ -f "$file" ]] || return 0
    while IFS= read -r line || [[ -n "$line" ]]; do
        line="${line%$'\r'}"
        [[ -z "${line//[[:space:]]/}" || "$line" =~ ^[[:space:]]*# ]] && continue
        if [[ ! "$line" =~ ^([A-Z][A-Z0-9_]*)=(.*)$ ]]; then
            printf 'Invalid KEY=value entry in %s\n' "$file" >&2
            return 1
        fi
        key="${BASH_REMATCH[1]}"
        value="${BASH_REMATCH[2]}"
        value="${value%$'\r'}"
        value="${value#"${value%%[![:space:]]*}"}"
        value="${value%"${value##*[![:space:]]}"}"
        if ((${#value} >= 2)); then
            if [[ "${value:0:1}" == '"' && "${value: -1}" == '"' ]] ||
               [[ "${value:0:1}" == "'" && "${value: -1}" == "'" ]]; then
                value="${value:1:${#value}-2}"
            fi
        fi
        existing_value="$(printenv "$key" 2>/dev/null || true)"
        if [[ -z "$existing_value" && -n "$value" ]]; then
            export "$key=$value"
        fi
    done < "$file"
}

environment_file="${ENV_FILE:-$SCRIPT_DIR/.env}"
if [[ ! -f "$environment_file" && -z "${ENV_FILE:-}" && -f "$SCRIPT_DIR/env" ]]; then
    environment_file="$SCRIPT_DIR/env"
fi
import_env_file "$environment_file"

MYSQL_HOST="${MYSQL_HOST:-localhost}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USERNAME="${MYSQL_USERNAME:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"
KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
SERVER_ADDRESS="${SERVER_ADDRESS:-127.0.0.1}"
EUREKA_INSTANCE_HOSTNAME="${EUREKA_INSTANCE_HOSTNAME:-127.0.0.1}"
EUREKA_INSTANCE_IP_ADDRESS="${EUREKA_INSTANCE_IP_ADDRESS:-127.0.0.1}"
EUREKA_INSTANCE_PREFER_IP_ADDRESS="${EUREKA_INSTANCE_PREFER_IP_ADDRESS:-true}"
export MYSQL_HOST MYSQL_PORT MYSQL_USERNAME MYSQL_PASSWORD KAFKA_BOOTSTRAP_SERVERS
export SERVER_ADDRESS EUREKA_INSTANCE_HOSTNAME EUREKA_INSTANCE_IP_ADDRESS EUREKA_INSTANCE_PREFER_IP_ADDRESS
export CONFIG_SERVER_URL="http://localhost:58888"
export EUREKA_SERVER_URL="http://localhost:58761/eureka"
export ORDER_SERVICE_URL="http://127.0.0.1:53311"
export ORDERS_SERVICE_URL="$ORDER_SERVICE_URL"
export PAYMENT_SERVICE_URL="http://127.0.0.1:53314"
export FULFILLMENT_SERVICE_URL="http://127.0.0.1:53315"
export NOTIFICATION_SERVICE_URL="http://127.0.0.1:53317"
export NOTIFICATIONS_SERVICE_URL="$NOTIFICATION_SERVICE_URL"
export CATALOG_SERVICE_URL="http://127.0.0.1:58089"
export CART_SERVICE_URL="http://127.0.0.1:58091"
export ORG_ACCESS_SERVICE_URL="http://127.0.0.1:53310"
export ORG_ACCESS_URL="$ORG_ACCESS_SERVICE_URL"
export INVENTORY_SERVICE_URL="http://127.0.0.1:58095"
export FLYWAY_ENABLED=false
export SPRING_FLYWAY_ENABLED=false
export SPRING_JPA_HIBERNATE_DDL_AUTO=validate
export SPRING_SQL_INIT_MODE=never
export CUSTOMER_MIGRATION_JDBC_URL="jdbc:mysql://$MYSQL_HOST:$MYSQL_PORT/customer_profiles?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
export CUSTOMER_MIGRATION_DB_USER="$MYSQL_USERNAME"
export CUSTOMER_MIGRATION_DB_PASSWORD="$MYSQL_PASSWORD"

CORE_SERVICES=(
    Ecom-eureka-server
    Ecom-config-server
    Ecom-Org-access
    customer-profile-service
    catalog-service
    inventory-service
    cart-service
    notification-service
    fulfillment-service
    payment-service
    order-service
    Ecom-api-gateway
)
ALL_SERVICES=("${CORE_SERVICES[@]}")
if ((ENABLE_LEGACY_ALERTS)); then
    ALL_SERVICES+=(Ecom-Alerts)
fi

contains_service() {
    local wanted="$1" service
    for service in "${SELECTED_SERVICES[@]}"; do
        [[ "$service" == "$wanted" ]] && return 0
    done
    return 1
}

SELECTED_SERVICES=()
if ((${#REQUESTED_SERVICES[@]} == 0)); then
    SELECTED_SERVICES=("${ALL_SERVICES[@]}")
else
    for requested in "${REQUESTED_SERVICES[@]}"; do
        normalized="$(printf '%s' "$requested" | tr '[:upper:]' '[:lower:]')"
        case "$normalized" in
            all|'*') SELECTED_SERVICES=("${ALL_SERVICES[@]}"); break ;;
            frontend|ecom-frontend) START_FRONTEND=1; continue ;;
            ecom-alerts)
                if ((!ENABLE_LEGACY_ALERTS)); then
                    printf 'Ecom-Alerts requires --legacy-alerts (and a running Kafka broker).\n' >&2
                    exit 2
                fi
                SELECTED_SERVICES+=(Ecom-Alerts)
                continue
                ;;
        esac
        matched=""
        for service in "${ALL_SERVICES[@]}"; do
            if [[ "$(printf '%s' "$service" | tr '[:upper:]' '[:lower:]')" == "$normalized" ]]; then
                matched="$service"
                break
            fi
        done
        if [[ -z "$matched" ]]; then
            printf 'Unknown service: %s\n' "$requested" >&2
            exit 2
        fi
        if ! contains_service "$matched"; then
            SELECTED_SERVICES+=("$matched")
        fi
    done
fi

service_port() {
    case "$1" in
        Ecom-eureka-server) printf '58761' ;;
        Ecom-config-server) printf '58888' ;;
        Ecom-Org-access) printf '53310' ;;
        customer-profile-service) printf '58088' ;;
        catalog-service) printf '58089' ;;
        inventory-service) printf '58095' ;;
        cart-service) printf '58091' ;;
        order-service) printf '53311' ;;
        payment-service) printf '53314' ;;
        fulfillment-service) printf '53315' ;;
        notification-service) printf '53317' ;;
        Ecom-api-gateway) printf '58082' ;;
        Ecom-Alerts) printf '58087' ;;
        Ecom-frontend) printf '55173' ;;
        *) return 1 ;;
    esac
}

port_is_open() {
    nc -z -w 2 "$1" "$2" >/dev/null 2>&1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || {
        printf 'Missing required command: %s\n' "$1" >&2
        return 1
    }
}

MYSQL_CLIENT=""

find_mysql_client() {
    local candidate brew_prefix
    MYSQL_CLIENT="$(command -v mysql 2>/dev/null || true)"
    [[ -n "$MYSQL_CLIENT" ]] && return 0

    for candidate in \
        /opt/homebrew/opt/mysql-client/bin/mysql \
        /opt/homebrew/opt/mysql/bin/mysql \
        /usr/local/opt/mysql-client/bin/mysql \
        /usr/local/opt/mysql/bin/mysql; do
        if [[ -x "$candidate" ]]; then
            MYSQL_CLIENT="$candidate"
            return 0
        fi
    done

    if command -v brew >/dev/null 2>&1; then
        brew_prefix="$(brew --prefix mysql-client 2>/dev/null || true)"
        if [[ -n "$brew_prefix" && -x "$brew_prefix/bin/mysql" ]]; then
            MYSQL_CLIENT="$brew_prefix/bin/mysql"
            return 0
        fi
    fi

    printf 'MySQL command-line client was not found (optional for startup).\n' >&2
    if command -v brew >/dev/null 2>&1; then
        printf 'Install it with: brew install mysql-client\n' >&2
    else
        printf 'Install the MySQL client and make its bin directory available on PATH.\n' >&2
    fi
    return 1
}

java_major_version() {
    local version_output="$1"
    if [[ "$version_output" =~ version[[:space:]]\"([0-9]+) ]]; then
        printf '%s' "${BASH_REMATCH[1]}"
        return 0
    fi
    return 1
}

select_java_21() {
    local candidate java_output
    local candidates=()
    [[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME")
    if [[ "$(uname -s)" == Darwin ]] && [[ -x /usr/libexec/java_home ]]; then
        candidate="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
        [[ -n "$candidate" ]] && candidates+=("$candidate")
    fi
    candidates+=("$TOOLS_DIR/java" "$SCRIPT_DIR/tools/java")
    candidates+=(/usr/lib/jvm/* /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home)

    for candidate in "${candidates[@]}"; do
        [[ -x "$candidate/bin/java" ]] || continue
        java_output="$("$candidate/bin/java" -version 2>&1 || true)"
        if [[ "$(java_major_version "$java_output" || true)" == 21 ]]; then
            JAVA_HOME="$candidate"
            PATH="$JAVA_HOME/bin:$PATH"
            export JAVA_HOME PATH
            return 0
        fi
    done
    return 1
}

is_tracked_process() {
    local name="$1" pid="$2" command_line
    [[ "$pid" =~ ^[0-9]+$ ]] || return 1
    command_line="$(ps -ww -p "$pid" -o command= 2>/dev/null || true)"
    [[ "$command_line" == *"-Dlauncher.service.name=$name"* ]]
}

stop_one() {
    local name="$1" pid_file="$PID_DIR/$1.pid" pid attempt
    [[ -f "$pid_file" ]] || { printf 'Already stopped: %s\n' "$name"; return 0; }
    pid="$(head -n 1 "$pid_file" 2>/dev/null || true)"
    if [[ -n "$pid" ]] && is_tracked_process "$name" "$pid"; then
        printf 'Stopping %s (PID %s)...\n' "$name" "$pid"
        kill -TERM "$pid" 2>/dev/null || true
        for ((attempt=0; attempt<15; attempt++)); do
            is_tracked_process "$name" "$pid" || break
            sleep 1
        done
        if is_tracked_process "$name" "$pid"; then
            printf 'Service %s did not stop after SIGTERM; sending SIGKILL.\n' "$name" >&2
            kill -KILL "$pid" 2>/dev/null || true
        fi
    elif [[ -n "$pid" ]]; then
        printf 'Not stopping PID %s for %s: it is not the tracked launcher process. Removing stale PID file.\n' "$pid" "$name"
    else
        printf 'Invalid PID file for %s; removing it.\n' "$name" >&2
    fi
    rm -f -- "$pid_file"
}

stop_selected() {
    local index
    if ((START_FRONTEND)); then stop_one Ecom-frontend; fi
    for ((index=${#SELECTED_SERVICES[@]}-1; index>=0; index--)); do
        stop_one "${SELECTED_SERVICES[index]}"
    done
    if contains_service Ecom-Alerts && [[ -f "$PID_DIR/__launcher-kafka.pid" ]]; then
        pid="$(head -n 1 "$PID_DIR/__launcher-kafka.pid" 2>/dev/null || true)"
        if [[ "$pid" =~ ^[0-9]+$ ]] && ps -ww -p "$pid" -o command= 2>/dev/null | grep -q 'kafka.Kafka'; then
            kill -TERM "$pid" 2>/dev/null || true
            printf 'Stopped Kafka started by this launcher (PID %s).\n' "$pid"
        fi
        rm -f -- "$PID_DIR/__launcher-kafka.pid"
    fi
}

start_kafka_if_needed() {
    local kafka_host kafka_port kafka_home kafka_start kafka_config pid attempt candidate
    kafka_host="${KAFKA_BOOTSTRAP_SERVERS%:*}"
    kafka_port="${KAFKA_BOOTSTRAP_SERVERS##*:}"
    if [[ "$kafka_host" == "$KAFKA_BOOTSTRAP_SERVERS" || ! "$kafka_port" =~ ^[0-9]+$ ]]; then
        printf 'KAFKA_BOOTSTRAP_SERVERS must be host:port (received %s).\n' "$KAFKA_BOOTSTRAP_SERVERS" >&2
        return 1
    fi
    if port_is_open "$kafka_host" "$kafka_port"; then
        printf 'Kafka is already available at %s:%s.\n' "$kafka_host" "$kafka_port"
        return 0
    fi

    kafka_home="${KAFKA_HOME:-}"
    if [[ -z "$kafka_home" ]]; then
        for candidate in "$TOOLS_DIR/kafka" "$SCRIPT_DIR/tools/kafka"; do
            if [[ -x "$candidate/bin/kafka-server-start.sh" ]]; then
                kafka_home="$candidate"
                break
            fi
        done
    fi
    kafka_start="$kafka_home/bin/kafka-server-start.sh"
    kafka_config="$kafka_home/config/kraft/server.properties"
    if [[ -z "$kafka_home" || ! -x "$kafka_start" || ! -f "$kafka_config" ]]; then
        printf 'Kafka is not reachable and its Unix launcher/config were not found. Set KAFKA_HOME to an installation containing bin/kafka-server-start.sh and config/kraft/server.properties.\n' >&2
        return 1
    fi
    if [[ -f "$PID_DIR/__launcher-kafka.pid" ]]; then
        pid="$(head -n 1 "$PID_DIR/__launcher-kafka.pid" 2>/dev/null || true)"
        if [[ "$pid" =~ ^[0-9]+$ ]] && ps -p "$pid" -o command= 2>/dev/null | grep -q 'kafka.Kafka'; then
            printf 'Kafka startup is already tracked (PID %s) but %s:%s is not accepting connections.\n' "$pid" "$kafka_host" "$kafka_port" >&2
            return 1
        fi
        rm -f -- "$PID_DIR/__launcher-kafka.pid"
    fi

    printf 'Starting Kafka from %s...\n' "$kafka_home"
    nohup "$kafka_start" "$kafka_config" >"$LOG_DIR/kafka.log" 2>&1 </dev/null &
    pid=$!
    printf '%s\n' "$pid" > "$PID_DIR/__launcher-kafka.pid"
    for ((attempt=0; attempt<90; attempt++)); do
        if port_is_open "$kafka_host" "$kafka_port"; then
            printf 'Kafka is ready at %s:%s (PID %s).\n' "$kafka_host" "$kafka_port" "$pid"
            return 0
        fi
        if ! kill -0 "$pid" 2>/dev/null; then
            printf 'Kafka exited during startup. Inspect %s/kafka.log.\n' "$LOG_DIR" >&2
            return 1
        fi
        sleep 1
    done
    printf 'Kafka did not become available at %s:%s. Inspect %s/kafka.log.\n' "$kafka_host" "$kafka_port" "$LOG_DIR" >&2
    return 1
}

if [[ "$ACTION" == stop ]]; then
    stop_selected
    printf '\nStop requested for: %s\n' "${SELECTED_SERVICES[*]:-none}"
    exit 0
fi

require_command java
require_command mvn
require_command nc
require_command curl
require_command lsof

select_java_21 || true
java_version_output="$(java -version 2>&1 || true)"
java_version="$(printf '%s\n' "$java_version_output" | head -n 1)"
java_major="$(java_major_version "$java_version_output" || true)"
if [[ "$java_major" != 21 ]]; then
    printf 'Java 21 is required; detected: %s\n' "$java_version" >&2
    printf 'Install a Java 21 JDK and set JAVA_HOME to it, or add it to PATH. On macOS, select it with: export JAVA_HOME=$(/usr/libexec/java_home -v 21)\n' >&2
    exit 1
fi
if ((START_FRONTEND)); then require_command npm; fi

mkdir -p "$LOG_DIR" "$PID_DIR"

mysql_available=0
needs_database=0
needs_sync=0
for service in "${SELECTED_SERVICES[@]}"; do
    case "$service" in
        Ecom-Org-access|customer-profile-service|catalog-service|inventory-service|cart-service|order-service|payment-service|fulfillment-service|notification-service|Ecom-Alerts)
            needs_database=1
            ;;
    esac
    case "$service" in
        cart-service|order-service|payment-service|fulfillment-service|notification-service|inventory-service)
            needs_sync=1
            ;;
    esac
done
if ((START_FRONTEND)); then require_command node; fi
if ((needs_database)); then
    if ! port_is_open "$MYSQL_HOST" "$MYSQL_PORT"; then
        printf 'MySQL is not reachable at %s:%s. Continuing startup without MySQL; database-dependent services may fail to become healthy.\n' "$MYSQL_HOST" "$MYSQL_PORT" >&2
    else
        mysql_available=1
        printf 'MySQL is available at %s:%s.\n' "$MYSQL_HOST" "$MYSQL_PORT"
    fi
    if ((mysql_available)) && [[ -z "$MYSQL_PASSWORD" ]]; then
        read -r -s -p "MySQL password for $MYSQL_USERNAME at $MYSQL_HOST:$MYSQL_PORT: " MYSQL_PASSWORD
        printf '\n'
        export MYSQL_PASSWORD CUSTOMER_MIGRATION_DB_PASSWORD="$MYSQL_PASSWORD"
    fi
fi

require_sync_key() {
    local key="$1" value
    value="$(printenv "$key" 2>/dev/null || true)"
    if [[ -z "$value" ]]; then
        read -r -s -p "Enter configured $key (at least 32 characters): " value
        printf '\n'
    fi
    if ((${#value} < 32)); then
        printf '%s must be configured with at least 32 characters.\n' "$key" >&2
        return 1
    fi
    printf -v "$key" '%s' "$value"
    export "$key"
}

mysql_query() {
    MYSQL_PWD="$MYSQL_PASSWORD" "$MYSQL_CLIENT" \
        --host="$MYSQL_HOST" --port="$MYSQL_PORT" --user="$MYSQL_USERNAME" \
        --batch --skip-column-names --execute="$1"
}

check_table() {
    local schema="$1" table="$2" columns="$3" count engine
    count="$(mysql_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$schema' AND table_name='$table'")" || {
        printf 'Read-only MySQL schema preflight failed for %s.\n' "$schema" >&2; return 1;
    }
    [[ "$count" == 1 ]] || {
        printf 'Missing %s.%s. Provision the reviewed schema before starting services.\n' "$schema" "$table" >&2
        return 1
    }
    mysql_query "SELECT $columns FROM \`$schema\`.\`$table\` LIMIT 0" >/dev/null || {
        printf 'Required columns are missing in %s.%s.\n' "$schema" "$table" >&2; return 1;
    }
    engine="$(mysql_query "SELECT engine FROM information_schema.tables WHERE table_schema='$schema' AND table_name='$table'")" || return 1
    [[ "$engine" == InnoDB ]] || {
        printf 'Transactional InnoDB is required for %s.%s.\n' "$schema" "$table" >&2; return 1;
    }
}

assert_sync_schemas() {
    local service schema lock_table table columns seed fee
    [[ -z "${MYSQL_DB:-}" ]] || {
        printf 'Unset the shared MYSQL_DB override: each service needs its own reviewed schema.\n' >&2; return 1;
    }
    for service in "${SELECTED_SERVICES[@]}"; do
        case "$service" in
            cart-service) schema=cart_service; lock_table=cart_write_lock ;;
            order-service) schema=order-service; lock_table=sync_receiver_lock ;;
            payment-service) schema=payment_service; lock_table=sync_receiver_lock ;;
            fulfillment-service) schema=fulfillment-service; lock_table=sync_receiver_lock ;;
            notification-service) schema=notification_service; lock_table=sync_receiver_lock ;;
            *) continue ;;
        esac
        if [[ "$service" == cart-service ]]; then
            check_table "$schema" cart_write_lock id
            check_table "$schema" cart_mutation_requests 'cart_id,request_key,request_hash,created_at'
            check_table "$schema" cart_checkout_claims 'cart_id,request_hash,created_at'
        else
            check_table "$schema" sync_receiver_lock id
            check_table "$schema" sync_inbox 'source,event_id,body_hash,received_at'
            check_table "$schema" sync_outbox 'id,event_id,target,aggregate_id,body,state,attempts,next_attempt,lease_token,lease_until,last_error'
            if [[ "$service" == order-service ]]; then
                check_table "$schema" order_creation_requests 'customer_id,request_key,request_hash,order_id'
            elif [[ "$service" == payment-service ]]; then
                check_table "$schema" provider_payment_bindings 'provider_payment_id,payment_id'
                check_table "$schema" provider_payment_receipts 'event_id,provider_payment_id,evidence_id,body_hash'
            fi
        fi
        seed="$(mysql_query "SELECT COUNT(*) FROM \`$schema\`.\`$lock_table\` WHERE id=1")" || return 1
        [[ "$seed" == 1 ]] || {
            printf 'Missing synchronization lock seed in %s.\n' "$schema" >&2; return 1;
        }
        if [[ "$service" == order-service ]]; then
            fee="$(mysql_query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$schema' AND table_name='orders' AND column_name='handling_fee' AND data_type='decimal' AND numeric_precision=10 AND numeric_scale=2 AND is_nullable='NO' AND column_default='0.00'")" || return 1
            [[ "$fee" == 1 ]] || {
                printf 'Order handling_fee requires compatible reviewed V10 schema.\n' >&2; return 1;
            }
        fi
    done
    printf 'Synchronization schemas passed read-only preflight.\n'
}

if ((needs_sync)); then
    for key in ORDER_SYNC_KEY PAYMENT_SYNC_KEY FULFILLMENT_SYNC_KEY INVENTORY_SYNC_KEY; do
        require_sync_key "$key"
    done
    if ((!mysql_available)); then
        printf 'Skipping MySQL synchronization schema checks because MySQL is unavailable.\n' >&2
    elif ! find_mysql_client; then
        printf 'Skipping MySQL synchronization schema checks because the MySQL client is unavailable. Continuing startup.\n' >&2
    elif [[ -n "${MYSQL_PASSWORD:-}" ]]; then
        assert_sync_schemas
    else
        printf 'MySQL password is required for synchronization schema checks.\n' >&2
        exit 1
    fi
    (
        cd "$SCRIPT_DIR/http-sync"
        mvn -DskipTests install
    )
fi

listener_pids() {
    local port="$1" result status
    # lsof returns 1 when there are no matching listeners.
    if result="$(lsof -nP -t -iTCP:"$port" -sTCP:LISTEN)"; then
        printf '%s\n' "$result" | sort -u
    else
        status=$?
        [[ "$status" == 1 ]] || return "$status"
    fi
}

clear_service_ports() {
    local service port pid attempt listeners remaining
    local services=("${SELECTED_SERVICES[@]}")
    local ports=()
    ((START_FRONTEND == 0)) || services+=(Ecom-frontend)
    for service in "${services[@]}"; do
        ports+=("$(service_port "$service")")
    done

    # Validate listener ownership before stopping any selected services.
    for port in "${ports[@]}"; do
        listeners="$(listener_pids "$port")" || return 1
        for pid in $listeners; do
            if [[ ! "$pid" =~ ^[0-9]+$ ]] || ((pid <= 1 || pid == $$ || pid == PPID)); then
                printf 'Port %s belongs to protected PID %s; cannot free it automatically.\n' "$port" "$pid" >&2
                return 1
            fi
        done
    done

    # Stop tracked Maven launchers too, so their PID files cannot skip relaunch.
    for service in "${services[@]}"; do
        stop_one "$service"
    done
    for port in "${ports[@]}"; do
        listeners="$(listener_pids "$port")" || return 1
        for pid in $listeners; do
            if ((pid <= 1 || pid == $$ || pid == PPID)); then
                printf 'Port %s belongs to protected PID %s.\n' "$port" "$pid" >&2
                return 1
            fi
            printf 'Stopping PID %s listening on port %s...\n' "$pid" "$port"
            kill -TERM "$pid" 2>/dev/null || true
        done
    done

    for ((attempt=0; attempt<20; attempt++)); do
        remaining=0
        for port in "${ports[@]}"; do
            listeners="$(listener_pids "$port")" || return 1
            [[ -n "$listeners" ]] || continue
            remaining=1
            if ((attempt == 15)); then
                for pid in $listeners; do
                    if ((pid <= 1 || pid == $$ || pid == PPID)); then
                        printf 'Port %s belongs to protected PID %s.\n' "$port" "$pid" >&2
                        return 1
                    fi
                    printf 'Port %s still occupied by PID %s; sending SIGKILL.\n' "$port" "$pid" >&2
                    kill -KILL "$pid" 2>/dev/null || true
                done
            fi
        done
        if ((remaining == 0)); then
            printf 'All selected service ports are available.\n'
            return 0
        fi
        sleep 1
    done
    printf 'Required service ports are still occupied; no application services were started.\n' >&2
    return 1
}

wait_for_health() {
    local name="$1" port="$2" attempt health_url="http://127.0.0.1:$2/actuator/health"
    for ((attempt=0; attempt<60; attempt++)); do
        if health="$(curl --silent --show-error --fail --max-time 3 "$health_url" 2>/dev/null)" &&
           [[ "$health" == *'"status":"UP"'* || "$health" == *'"status": "UP"'* ]]; then
            printf 'Healthy: %s\n' "$name"
            return 0
        fi
        sleep 2
    done
    printf 'Service %s did not become healthy. Inspect %s/%s.log; startup is not verified.\n' "$name" "$LOG_DIR" "$name" >&2
    return 1
}

start_service() {
    local name="$1" directory="$SCRIPT_DIR/$1" pid_file="$PID_DIR/$1.pid" port pid
    [[ "$name" == Ecom-frontend ]] && directory="$REPO_DIR/Ecom-frontend"
    if [[ ! -d "$directory" ]]; then
        printf 'Service directory not found: %s\n' "$directory" >&2
        return 1
    fi
    if [[ -f "$pid_file" ]]; then
        pid="$(head -n 1 "$pid_file" 2>/dev/null || true)"
        if [[ -n "$pid" ]] && is_tracked_process "$name" "$pid"; then
            printf 'Already running: %s (PID %s)\n' "$name" "$pid"
            return 0
        fi
        rm -f -- "$pid_file"
    fi
    port="$(service_port "$name")"
    if port_is_open 127.0.0.1 "$port"; then
        printf 'Port %s for %s is occupied; refusing to start it.\n' "$port" "$name" >&2
        return 1
    fi
    if [[ "$name" == Ecom-frontend ]]; then
        if [[ ! -d "$directory/node_modules" ]]; then
            printf 'Installing frontend dependencies...\n'
            if ! (cd "$directory" && npm install) >>"$LOG_DIR/$name.log" 2>&1; then
                printf 'Frontend dependency installation failed. Inspect %s/%s.log.\n' "$LOG_DIR" "$name" >&2
                return 1
            fi
        fi
        nohup bash -c 'cd "$1" || exit; exec node --title=launcher.service.name=Ecom-frontend node_modules/vite/bin/vite.js --host 127.0.0.1 --port 55173 --strictPort' \
            launcher.service.name=Ecom-frontend "$directory" >"$LOG_DIR/$name.log" 2>&1 </dev/null &
    else
        nohup bash -c 'cd "$1" || exit; export SERVER_PORT="$2" SERVER_ADDRESS="$SERVER_ADDRESS" EUREKA_INSTANCE_HOSTNAME="$EUREKA_INSTANCE_HOSTNAME" EUREKA_INSTANCE_IP_ADDRESS="$EUREKA_INSTANCE_IP_ADDRESS" EUREKA_INSTANCE_PREFER_IP_ADDRESS="$EUREKA_INSTANCE_PREFER_IP_ADDRESS" EUREKA_CLIENT_ENABLED=true; exec mvn "$0" -DskipTests spring-boot:run' \
            "-Dlauncher.service.name=$name" "$directory" "$port" "$name" >"$LOG_DIR/$name.log" 2>&1 </dev/null &
    fi
    pid=$!
    printf '%s\n' "$pid" > "$pid_file"
    printf 'Started %s (PID %s) -> %s/%s.log\n' "$name" "$pid" "$LOG_DIR" "$name"
    if [[ "$name" != Ecom-frontend ]]; then wait_for_health "$name" "$port"; fi
}

if [[ "$ACTION" == restart ]]; then
    stop_selected
fi

clear_service_ports
if contains_service Ecom-Alerts; then start_kafka_if_needed; fi

if ((needs_database)); then
    printf 'Core services use HTTP synchronization; Kafka is not started unless --legacy-alerts is selected.\n'
fi

STARTUP_GROUPS=(
    'Ecom-eureka-server'
    'Ecom-config-server'
    'Ecom-Org-access customer-profile-service catalog-service inventory-service cart-service notification-service fulfillment-service payment-service order-service Ecom-api-gateway'
)
if ((ENABLE_LEGACY_ALERTS)); then STARTUP_GROUPS+=('Ecom-Alerts'); fi

for group in "${STARTUP_GROUPS[@]}"; do
    group_started=0
    for service in $group; do
        if contains_service "$service"; then
            if ((needs_database && !mysql_available)); then
                start_service "$service" || printf 'Startup could not be verified for %s; continuing with the remaining services.\n' "$service" >&2
            else
                start_service "$service"
            fi
            group_started=1
        fi
    done
    ((group_started == 0)) || sleep 15
done

if ((START_FRONTEND)); then start_service Ecom-frontend; fi

printf '\nStartup requested for: %s\n' "${SELECTED_SERVICES[*]}"
if ((START_FRONTEND)); then printf 'Frontend: http://127.0.0.1:55173\n'; fi
printf 'Gateway:  http://localhost:58082\nEureka:   http://localhost:58761\nLogs:     %s\n' "$LOG_DIR"
