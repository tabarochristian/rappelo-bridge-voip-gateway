#!/bin/bash

# GSM-SIP Gateway Build Script
# This script builds the Android application with proper configuration

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  GSM-SIP Gateway Build Script${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

# Check for required tools
check_requirements() {
    echo -e "${YELLOW}Checking requirements...${NC}"
    
    if ! command -v java &> /dev/null; then
        echo -e "${RED}Error: Java is not installed${NC}"
        exit 1
    fi
    
    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -lt 17 ]; then
        echo -e "${RED}Error: Java 17+ is required (found Java $JAVA_VERSION)${NC}"
        exit 1
    fi
    
    echo -e "${GREEN}✓ Java $JAVA_VERSION found${NC}"
    
    if [ ! -f "gradlew" ]; then
        echo -e "${RED}Error: gradlew not found. Run from project root.${NC}"
        exit 1
    fi
    
    echo -e "${GREEN}✓ Gradle wrapper found${NC}"
    
    # Check for PJSIP AAR
    if [ ! -f "app/libs/pjsua2.aar" ]; then
        echo -e "${YELLOW}⚠ Warning: app/libs/pjsua2.aar not found${NC}"
        echo -e "${YELLOW}  SIP functionality will not work without PJSIP${NC}"
        echo -e "${YELLOW}  Download from: https://github.com/pjsip/pjproject${NC}"
    else
        echo -e "${GREEN}✓ PJSIP AAR found${NC}"
    fi
    
    echo ""
}

# Clean build
clean() {
    echo -e "${YELLOW}Cleaning previous build...${NC}"
    ./gradlew clean
    echo -e "${GREEN}✓ Clean complete${NC}"
    echo ""
}

# Build debug APK
build_debug() {
    echo -e "${YELLOW}Building debug APK...${NC}"
    ./gradlew assembleDebug
    
    if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
        echo -e "${GREEN}✓ Debug APK built successfully${NC}"
        echo -e "  Location: app/build/outputs/apk/debug/app-debug.apk"
    else
        echo -e "${RED}Error: Debug APK not found${NC}"
        exit 1
    fi
    echo ""
}

# Build release APK
build_release() {
    echo -e "${YELLOW}Building release APK...${NC}"
    
    if [ -z "$KEYSTORE_FILE" ]; then
        echo -e "${YELLOW}⚠ KEYSTORE_FILE not set, building unsigned APK${NC}"
        ./gradlew assembleRelease
    else
        ./gradlew assembleRelease \
            -Pandroid.injected.signing.store.file="$KEYSTORE_FILE" \
            -Pandroid.injected.signing.store.password="$KEYSTORE_PASSWORD" \
            -Pandroid.injected.signing.key.alias="$KEY_ALIAS" \
            -Pandroid.injected.signing.key.password="$KEY_PASSWORD"
    fi
    
    if [ -f "app/build/outputs/apk/release/app-release.apk" ]; then
        echo -e "${GREEN}✓ Release APK built successfully${NC}"
        echo -e "  Location: app/build/outputs/apk/release/app-release.apk"
    elif [ -f "app/build/outputs/apk/release/app-release-unsigned.apk" ]; then
        echo -e "${GREEN}✓ Unsigned release APK built${NC}"
        echo -e "  Location: app/build/outputs/apk/release/app-release-unsigned.apk"
    fi
    echo ""
}

# Run tests
run_tests() {
    echo -e "${YELLOW}Running unit tests...${NC}"
    ./gradlew testDebugUnitTest
    echo -e "${GREEN}✓ Tests complete${NC}"
    echo ""
}

# Run lint
run_lint() {
    echo -e "${YELLOW}Running lint checks...${NC}"
    ./gradlew lintDebug
    echo -e "${GREEN}✓ Lint complete${NC}"
    echo -e "  Report: app/build/reports/lint-results-debug.html"
    echo ""
}

# Install on device
install_debug() {
    echo -e "${YELLOW}Installing debug APK on device...${NC}"
    ./gradlew installDebug
    echo -e "${GREEN}✓ Installation complete${NC}"
    echo ""
}

# Show help
show_help() {
    echo "Usage: ./build.sh [command]"
    echo ""
    echo "Commands:"
    echo "  debug     Build debug APK (default)"
    echo "  release   Build release APK"
    echo "  clean     Clean build files"
    echo "  test      Run unit tests"
    echo "  lint      Run lint checks"
    echo "  install   Install debug APK on connected device"
    echo "  all       Clean, test, and build release"
    echo "  help      Show this help message"
    echo ""
    echo "Environment variables for signed release:"
    echo "  KEYSTORE_FILE      Path to keystore file"
    echo "  KEYSTORE_PASSWORD  Keystore password"
    echo "  KEY_ALIAS          Key alias"
    echo "  KEY_PASSWORD       Key password"
}

# Main
main() {
    check_requirements
    
    case "${1:-debug}" in
        debug)
            build_debug
            ;;
        release)
            build_release
            ;;
        clean)
            clean
            ;;
        test)
            run_tests
            ;;
        lint)
            run_lint
            ;;
        install)
            build_debug
            install_debug
            ;;
        all)
            clean
            run_tests
            run_lint
            build_release
            ;;
        help|--help|-h)
            show_help
            ;;
        *)
            echo -e "${RED}Unknown command: $1${NC}"
            show_help
            exit 1
            ;;
    esac
    
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}  Build completed successfully!${NC}"
    echo -e "${GREEN}========================================${NC}"
}

main "$@"
