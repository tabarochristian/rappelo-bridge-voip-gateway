# GSM-SIP Gateway

A production-grade platform for bridging GSM telephony with SIP/VoIP, consisting of:

- **Mobile App** (`mobile/`) — Android app that acts as a GSM ↔ SIP bridge device
- **SIP Server** (`server/`) — Dockerized Kamailio + RTPEngine + coturn stack

## Quick Start

### 1. Deploy the SIP Server (one command)

```bash
# Clone and deploy
cp .env.example .env          # Edit with your domain/IP
./deploy.sh up                # Builds and starts everything
```

### 2. Create a SIP Account

```bash
./server/scripts/manage-accounts.sh add gateway1 MySecurePassword
```

### 3. Configure & Install the Mobile App

Build the APK from `mobile/`, install on an Android 10+ device with a SIM card, then configure:

| Setting      | Value                              |
|--------------|------------------------------------|
| SIP Server   | `your-server-ip` or `sip.domain`   |
| SIP Port     | `5060`                             |
| Username     | `gateway1`                         |
| Password     | `MySecurePassword`                 |
| Domain       | `sip.rappelo.local` (your domain)  |
| STUN Server  | `your-server-ip:3478`              |

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                        CLOUD / SERVER                                │
│                                                                      │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────┐  ┌────────────┐ │
│  │  Kamailio   │  │  RTPEngine   │  │  coturn    │  │ PostgreSQL │ │
│  │  SIP Proxy  │──│  Media Relay │  │ STUN/TURN  │  │ Subscriber │ │
│  │  Port 5060  │  │  RTP 30000+  │  │ Port 3478  │  │   DB/CDR   │ │
│  └──────┬──────┘  └──────────────┘  └────────────┘  └────────────┘ │
│         │              SIP + RTP                                     │
└─────────┼────────────────────────────────────────────────────────────┘
          │ Internet / NAT
┌─────────┼────────────────────────────────────────────────────────────┐
│         │           MOBILE DEVICE (Android 10+)                      │
│  ┌──────▼──────┐                                                     │
│  │  PJSIP 2.14 │──── SIP Registration + Calls                       │
│  └──────┬──────┘                                                     │
│         │                                                            │
│  ┌──────▼──────────────────────────────────────────────────────────┐ │
│  │                    Gateway App                                   │ │
│  │  CallBridge │ AudioRouter │ CallQueue │ SmsQueue │ CommandPoller │ │
│  └──────┬──────────────────────────────────────────────────────────┘ │
│         │                                                            │
│  ┌──────▼──────┐                                                     │
│  │  GSM Modem  │──── Phone Calls + SMS via SIM Card                  │
│  └─────────────┘                                                     │
└──────────────────────────────────────────────────────────────────────┘
```

## Server Stack

| Component      | Image                     | Purpose                                |
|----------------|---------------------------|----------------------------------------|
| **Kamailio**   | kamailio/kamailio-ci:5.8  | SIP registrar, proxy, authentication   |
| **RTPEngine**  | droopygit/rtpengine       | Media relay, NAT traversal for RTP     |
| **coturn**     | coturn/coturn             | STUN/TURN for ICE NAT traversal        |
| **PostgreSQL** | postgres:16-alpine        | Subscriber accounts, CDR, dialog state |
| **Redis**      | redis:7-alpine            | Caching layer                          |

### Server Management

```bash
./deploy.sh up          # Start all services
./deploy.sh down        # Stop all services
./deploy.sh status      # Check service health
./deploy.sh logs        # Tail all logs
./deploy.sh logs kamailio  # Tail specific service

# Account management
./server/scripts/manage-accounts.sh add <user> <password>
./server/scripts/manage-accounts.sh list
./server/scripts/manage-accounts.sh online    # Show registered devices
./server/scripts/manage-accounts.sh cdr       # Call detail records
./server/scripts/manage-accounts.sh passwd <user> <new-pass>
./server/scripts/manage-accounts.sh remove <user>
```

### Environment Configuration (`.env`)

| Variable               | Default                          | Description                    |
|------------------------|----------------------------------|--------------------------------|
| `SIP_DOMAIN`           | `sip.rappelo.local`              | SIP domain                     |
| `PUBLIC_IP`            | *(empty)*                        | Server public IP for NAT       |
| `KAMAILIO_DB_PASSWORD` | `kamailio-secret-change-me`      | PostgreSQL password            |
| `TURN_SECRET`          | `rappelo-turn-secret-change-me`  | TURN auth shared secret        |
| `SIP_PORT`             | `5060`                           | SIP signaling port             |
| `ENABLE_TLS`           | `false`                          | Enable SIP-TLS on port 5061    |
| `DEBUG`                | `false`                          | Verbose Kamailio logging       |

### Firewall Ports

| Port          | Protocol | Service            |
|---------------|----------|--------------------|
| 5060          | UDP/TCP  | SIP signaling      |
| 5061          | TCP      | SIP-TLS            |
| 3478          | UDP/TCP  | STUN/TURN          |
| 5349          | UDP/TCP  | TURN-TLS           |
| 30000-30100   | UDP      | RTP media           |
| 49152-49200   | UDP      | TURN relay          |

---

## Mobile App

Android 10+ (API 29–34) app that bridges GSM telephony with SIP/VoIP using PJSIP 2.14.

### Requirements

- Android device with SIM card
- PJSIP 2.14 AAR in `mobile/app/libs/pjsua2.aar`
- Android SDK (NDK 26.3, Java 21)

### Build

```bash
cd mobile
./build.sh          # or: ./gradlew assembleDebug
```

Or open `mobile/` in Android Studio, sync Gradle, and build.

### Deploy as System App (rooted device)

```bash
adb root
adb remount
adb push mobile/app/build/outputs/apk/debug/app-debug.apk /system/priv-app/RappeloBridge/RappeloBridge.apk
adb shell pm set-install-location 1
adb reboot
```

### Local HTTP API (port 8080)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Health check |
| GET | `/status` | Gateway status |
| POST | `/call` | Initiate outgoing call |
| POST | `/call/hangup` | Hang up call |
| POST | `/sms` | Send SMS |
| GET | `/queue/calls` | Get queued calls |
| GET | `/queue/sms` | Get queued SMS |
| GET | `/logs` | Get recent logs (supports `?level=`, `?tag=`, `?search=`, `?limit=`) |

### Mobile Tech Stack

- **Kotlin 2.0.21** / **KSP 2.0.21-1.0.28** — Pure Kotlin
- **Hilt 2.48** — Dependency injection
- **Room** — SQLite database with KSP annotation processing
- **PJSIP 2.14** — SIP stack (arm64-v8a)
- **Retrofit 2.9 + OkHttp 4.12** — HTTP client
- **Kotlin Serialization** — JSON parsing
- **NanoHTTPD 2.3.1** — Local HTTP server
- **WorkManager + AlarmManager** — Background persistence
- **Gradle 8.14 / AGP 8.11.1**

### Testing

```bash
cd mobile
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest   # requires device
```

---

## Project Structure

```
├── docker-compose.yml           # Full SIP server orchestration
├── .env.example                 # Environment template
├── deploy.sh                    # One-click deploy script
├── server/
│   ├── kamailio/                # SIP proxy/registrar
│   │   ├── Dockerfile
│   │   ├── kamailio.cfg
│   │   ├── tls.cfg
│   │   └── entrypoint.sh
│   ├── rtpengine/               # Media relay
│   │   ├── Dockerfile
│   │   └── entrypoint.sh
│   ├── coturn/                  # STUN/TURN server
│   │   ├── Dockerfile
│   │   ├── turnserver.conf
│   │   └── entrypoint.sh
│   ├── postgres/initdb/         # DB schema
│   │   ├── 01-kamailio-schema.sql
│   │   └── 02-seed-data.sql
│   └── scripts/
│       └── manage-accounts.sh
└── mobile/                      # Android app
    ├── app/
    │   ├── libs/pjsua2.aar
    │   └── src/main/kotlin/com/gateway/
    ├── build.gradle.kts
    ├── build.sh
    ├── gradlew
    └── settings.gradle.kts
```

## License

MIT License

## Support

For issues and feature requests, please use GitHub Issues.
