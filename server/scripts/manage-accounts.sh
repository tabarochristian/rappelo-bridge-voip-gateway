#!/bin/bash
##
## Rappelo Bridge - SIP Account Management Script
## Usage: ./manage-accounts.sh <command> [args]
##

set -e

# Load env
if [ -f .env ]; then
    export $(grep -v '^#' .env | xargs)
fi

DB_NAME="${KAMAILIO_DB_NAME:-kamailio}"
DB_USER="${KAMAILIO_DB_USER:-kamailio}"
DB_PASS="${KAMAILIO_DB_PASSWORD:-kamailio-secret-change-me}"
SIP_DOMAIN="${SIP_DOMAIN:-sip.rappelo.local}"
CONTAINER="rappelo-postgres"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# Sanitize input to prevent SQL injection
sanitize() {
    local val="$1"
    # Reject inputs containing null bytes or suspicious patterns
    if [[ "$val" =~ [\;\\] ]] || [[ "$val" == *"--"* ]]; then
        echo -e "${RED}Error: Invalid characters in input${NC}" >&2
        exit 1
    fi
    # Escape single quotes for SQL
    val="${val//\'/\'\'}"
    echo "$val"
}

db_exec() {
    docker exec -e PGPASSWORD="$DB_PASS" "$CONTAINER" \
        psql -U "$DB_USER" -d "$DB_NAME" -t -A -c "$1"
}

# Generate HA1 hash: MD5(username:domain:password)
gen_ha1() {
    echo -n "$1:$2:$3" | md5sum | cut -d' ' -f1
}

# Generate HA1B hash: MD5(username@domain:domain:password)
gen_ha1b() {
    echo -n "$1@$2:$2:$3" | md5sum | cut -d' ' -f1
}

cmd_add() {
    local username="$1"
    local password="$2"
    local domain="${3:-$SIP_DOMAIN}"

    if [ -z "$username" ] || [ -z "$password" ]; then
        echo -e "${RED}Usage: $0 add <username> <password> [domain]${NC}"
        exit 1
    fi

    # Sanitize all inputs
    username=$(sanitize "$username")
    password=$(sanitize "$password")
    domain=$(sanitize "$domain")

    local ha1=$(gen_ha1 "$username" "$domain" "$password")
    local ha1b=$(gen_ha1b "$username" "$domain" "$password")

    db_exec "INSERT INTO subscriber (username, domain, ha1, ha1b, datetime_created) \
             VALUES ('$username', '$domain', '$ha1', '$ha1b', NOW()) \
             ON CONFLICT (username, domain) DO UPDATE \
             SET ha1='$ha1', ha1b='$ha1b';"

    echo -e "${GREEN}Account created/updated:${NC}"
    echo -e "  SIP URI  : ${CYAN}sip:${username}@${domain}${NC}"
    echo -e "  Username : ${CYAN}${username}${NC}"
    echo -e "  Domain   : ${CYAN}${domain}${NC}"
}

cmd_remove() {
    local username="$1"
    local domain="${2:-$SIP_DOMAIN}"

    if [ -z "$username" ]; then
        echo -e "${RED}Usage: $0 remove <username> [domain]${NC}"
        exit 1
    fi

    username=$(sanitize "$username")
    domain=$(sanitize "$domain")

    db_exec "DELETE FROM subscriber WHERE username='$username' AND domain='$domain';"
    echo -e "${GREEN}Account removed: sip:${username}@${domain}${NC}"
}

cmd_list() {
    echo -e "${CYAN}SIP Accounts:${NC}"
    echo "-------------------------------------------"
    printf "%-20s %-30s %-25s\n" "USERNAME" "DOMAIN" "CREATED"
    echo "-------------------------------------------"
    db_exec "SELECT username, domain, to_char(datetime_created, 'YYYY-MM-DD HH24:MI:SS') FROM subscriber ORDER BY username;" | \
        while IFS='|' read -r user dom created; do
            printf "%-20s %-30s %-25s\n" "$user" "$dom" "$created"
        done
    echo "-------------------------------------------"
    local count=$(db_exec "SELECT COUNT(*) FROM subscriber;")
    echo -e "Total: ${GREEN}${count}${NC} accounts"
}

cmd_online() {
    echo -e "${CYAN}Online Registrations:${NC}"
    echo "--------------------------------------------------------------------"
    printf "%-18s %-25s %-30s\n" "USERNAME" "USER-AGENT" "CONTACT"
    echo "--------------------------------------------------------------------"
    db_exec "SELECT username, user_agent, contact FROM location WHERE expires > NOW() ORDER BY username;" | \
        while IFS='|' read -r user ua contact; do
            printf "%-18s %-25s %-30s\n" "$user" "${ua:0:24}" "${contact:0:29}"
        done
    echo "--------------------------------------------------------------------"
    local count=$(db_exec "SELECT COUNT(*) FROM location WHERE expires > NOW();")
    echo -e "Online: ${GREEN}${count}${NC} devices"
}

cmd_passwd() {
    local username="$1"
    local password="$2"
    local domain="${3:-$SIP_DOMAIN}"

    if [ -z "$username" ] || [ -z "$password" ]; then
        echo -e "${RED}Usage: $0 passwd <username> <new-password> [domain]${NC}"
        exit 1
    fi

    username=$(sanitize "$username")
    password=$(sanitize "$password")
    domain=$(sanitize "$domain")

    local ha1=$(gen_ha1 "$username" "$domain" "$password")
    local ha1b=$(gen_ha1b "$username" "$domain" "$password")

    db_exec "UPDATE subscriber SET ha1='$ha1', ha1b='$ha1b' \
             WHERE username='$username' AND domain='$domain';"

    echo -e "${GREEN}Password updated for sip:${username}@${domain}${NC}"
}

cmd_cdr() {
    local limit="${1:-20}"
    echo -e "${CYAN}Recent Call Records (last $limit):${NC}"
    echo "--------------------------------------------------------------------------------"
    printf "%-20s %-12s %-12s %-15s %-6s %-15s\n" "TIME" "FROM" "TO" "DST_DOMAIN" "CODE" "METHOD"
    echo "--------------------------------------------------------------------------------"
    db_exec "SELECT to_char(time, 'YYYY-MM-DD HH24:MI'), src_user, dst_user, dst_domain, sip_code, method \
             FROM acc ORDER BY time DESC LIMIT $limit;" | \
        while IFS='|' read -r t src dst dom code method; do
            printf "%-20s %-12s %-12s %-15s %-6s %-15s\n" "$t" "$src" "$dst" "$dom" "$code" "$method"
        done
    echo "--------------------------------------------------------------------------------"
}

# ---- Main ----
case "${1:-help}" in
    add)      cmd_add "$2" "$3" "$4" ;;
    remove)   cmd_remove "$2" "$3" ;;
    list)     cmd_list ;;
    online)   cmd_online ;;
    passwd)   cmd_passwd "$2" "$3" "$4" ;;
    cdr)      cmd_cdr "$2" ;;
    *)
        echo -e "${CYAN}Rappelo Bridge - SIP Account Manager${NC}"
        echo ""
        echo "Usage: $0 <command> [args]"
        echo ""
        echo "Commands:"
        echo "  add <user> <pass> [domain]    Create or update a SIP account"
        echo "  remove <user> [domain]        Delete a SIP account"
        echo "  passwd <user> <pass> [domain] Change account password"
        echo "  list                          List all SIP accounts"
        echo "  online                        Show currently registered devices"
        echo "  cdr [limit]                   Show recent call records"
        echo ""
        echo "Examples:"
        echo "  $0 add gateway1 MySecurePass123"
        echo "  $0 add device02 Pass456 sip.mycompany.com"
        echo "  $0 online"
        echo "  $0 cdr 50"
        ;;
esac
