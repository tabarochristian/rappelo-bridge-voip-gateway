#!/bin/bash
##
## Rappelo Bridge VoIP Gateway - Deploy Script
## One-click deployment of the full SIP server stack
##
## Usage: ./deploy.sh [up|down|restart|status|logs]
##

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

header() {
    echo ""
    echo -e "${CYAN}╔══════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}║   Rappelo Bridge - VoIP Server Deploy    ║${NC}"
    echo -e "${CYAN}╚══════════════════════════════════════════╝${NC}"
    echo ""
}

check_deps() {
    for cmd in docker; do
        if ! command -v $cmd &>/dev/null; then
            echo -e "${RED}Error: '$cmd' is not installed.${NC}"
            exit 1
        fi
    done

    if ! docker compose version &>/dev/null 2>&1; then
        echo -e "${RED}Error: 'docker compose' plugin not available.${NC}"
        exit 1
    fi
}

init_env() {
    if [ ! -f .env ]; then
        echo -e "${YELLOW}No .env file found. Creating from template...${NC}"
        cp .env.example .env
        echo -e "${GREEN}.env file created. Please review and update:${NC}"
        echo -e "  ${CYAN}$SCRIPT_DIR/.env${NC}"
        echo ""
        echo -e "${YELLOW}Key settings to configure:${NC}"
        echo "  SIP_DOMAIN          - Your SIP domain"
        echo "  PUBLIC_IP           - Server's public IP (for NAT traversal)"
        echo "  KAMAILIO_DB_PASSWORD - Database password"
        echo "  TURN_SECRET         - TURN server shared secret"
        echo ""
        read -p "Press Enter to continue with defaults, or Ctrl+C to edit .env first... "
    fi
    source .env 2>/dev/null || true
}

cmd_up() {
    header
    check_deps
    init_env

    echo -e "${YELLOW}Building and starting SIP server stack...${NC}"
    echo ""

    docker compose build --parallel
    docker compose up -d

    echo ""
    echo -e "${GREEN}Stack is starting up. Waiting for health checks...${NC}"
    sleep 5

    # Check services
    echo ""
    cmd_status

    echo ""
    echo -e "${GREEN}═══════════════════════════════════════════${NC}"
    echo -e "${GREEN}  Deployment complete!${NC}"
    echo -e "${GREEN}═══════════════════════════════════════════${NC}"
    echo ""
    echo -e "SIP Server   : ${CYAN}${SIP_DOMAIN:-sip.rappelo.local}:${SIP_PORT:-5060}${NC}"
    echo -e "STUN/TURN    : ${CYAN}${SIP_DOMAIN:-sip.rappelo.local}:${STUN_PORT:-3478}${NC}"
    echo -e "Admin Panel  : ${CYAN}http://localhost:${ADMIN_PORT:-8080}${NC}"
    echo -e "TURN Secret  : ${CYAN}(see .env TURN_SECRET)${NC}"
    echo ""
    echo -e "${YELLOW}Next steps:${NC}"
    echo "  1. Create a SIP account:"
    echo -e "     ${CYAN}./server/scripts/manage-accounts.sh add gateway1 YourPassword${NC}"
    echo ""
    echo "  2. Configure the mobile app:"
    echo "     SIP Server  : ${SIP_DOMAIN:-sip.rappelo.local}"
    echo "     SIP Port    : ${SIP_PORT:-5060}"
    echo "     STUN Server : ${SIP_DOMAIN:-sip.rappelo.local}:${STUN_PORT:-3478}"
    echo "     TURN Server : ${SIP_DOMAIN:-sip.rappelo.local}:${STUN_PORT:-3478}"
    echo "     TURN Secret : (from .env)"
    echo ""
}

cmd_down() {
    header
    echo -e "${YELLOW}Stopping SIP server stack...${NC}"
    docker compose down
    echo -e "${GREEN}All services stopped.${NC}"
}

cmd_restart() {
    header
    echo -e "${YELLOW}Restarting SIP server stack...${NC}"
    docker compose restart
    sleep 3
    cmd_status
}

cmd_status() {
    echo -e "${CYAN}Service Status:${NC}"
    echo "──────────────────────────────────────────"

    local services=("rappelo-postgres" "rappelo-kamailio" "rappelo-rtpengine" "rappelo-coturn" "rappelo-admin")
    local labels=("PostgreSQL  " "Kamailio    " "RTPEngine   " "coturn      " "Admin Panel ")

    for i in "${!services[@]}"; do
        local status=$(docker inspect -f '{{.State.Status}}' "${services[$i]}" 2>/dev/null || echo "not found")
        local health=$(docker inspect -f '{{.State.Health.Status}}' "${services[$i]}" 2>/dev/null || echo "")

        if [ "$status" = "running" ]; then
            if [ "$health" = "healthy" ] || [ -z "$health" ]; then
                echo -e "  ${labels[$i]}  ${GREEN}● running${NC}"
            else
                echo -e "  ${labels[$i]}  ${YELLOW}● $status ($health)${NC}"
            fi
        else
            echo -e "  ${labels[$i]}  ${RED}○ $status${NC}"
        fi
    done
    echo "──────────────────────────────────────────"
}

cmd_logs() {
    local service="$1"
    if [ -n "$service" ]; then
        docker compose logs -f "$service"
    else
        docker compose logs -f
    fi
}

# ---- Main ----
case "${1:-up}" in
    up)       cmd_up ;;
    down)     cmd_down ;;
    restart)  cmd_restart ;;
    status)   cmd_status ;;
    logs)     cmd_logs "$2" ;;
    *)
        echo "Usage: $0 {up|down|restart|status|logs [service]}"
        echo ""
        echo "Commands:"
        echo "  up        Build and start all services (default)"
        echo "  down      Stop and remove all services"
        echo "  restart   Restart all services"
        echo "  status    Show service status"
        echo "  logs      Tail logs (optionally for specific service)"
        ;;
esac
