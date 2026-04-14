# PJSIP Library

Place the PJSIP 2.14 AAR file here:

```
pjsua2.aar
```

## Download

Download pre-built PJSIP AAR from:
- https://github.com/pjsip/pjproject/releases

Or build from source:
- https://github.com/pjsip/pjproject

## Build Instructions

If building from source:

1. Clone PJSIP repository
2. Configure for Android NDK
3. Build with `./configure-android` and `make dep && make`
4. Package as AAR

The AAR should contain:
- libpjsua2.so for arm64-v8a, armeabi-v7a, x86, x86_64
- Java/Kotlin bindings for pjsua2 API
