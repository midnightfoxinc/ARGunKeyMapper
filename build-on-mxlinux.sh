#!/usr/bin/env bash
#
# Build ARGUN Mapper on mxLinux / Ubuntu / Debian (and most other Linux distros).
#
# Installs a private JDK + Android SDK under $HOME if they are missing, then
# builds the debug APK. No root access is required.
#
#   ./build-on-mxlinux.sh              # build debug APK
#   ./build-on-mxlinux.sh --install    # build, then adb install if a device is attached
#
set -euo pipefail

GRADLE_VERSION="8.6"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
JDK_VERSION="17.0.13+11"
TOOLS_DIR="$HOME/tools"
JDK_DIR="$TOOLS_DIR/jdk-$JDK_VERSION"
GRADLE_DIR="$TOOLS_DIR/gradle-$GRADLE_VERSION"

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

info()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
ok()    { printf '\033[1;32m  ok\033[0m %s\n' "$*"; }
warn()  { printf '\033[1;33m  !!\033[0m %s\n' "$*"; }
die()   { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- JDK

install_jdk() {
    if [[ -x "$JDK_DIR/bin/jlink" ]]; then
        ok "JDK found at $JDK_DIR"
        return
    fi

    info "Installing Temurin JDK $JDK_VERSION (no root needed)"
    mkdir -p "$TOOLS_DIR"
    local tarball="$TOOLS_DIR/jdk.tar.gz"
    curl -fsSL -o "$tarball" \
        "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-${JDK_VERSION//+/%2B}/OpenJDK17U-jdk_x64_linux_hotspot_${JDK_VERSION}_11.tar.gz"
    tar xzf "$tarball" -C "$TOOLS_DIR"
    rm -f "$tarball"
    [[ -x "$JDK_DIR/bin/jlink" ]] || die "JDK extraction failed"
    ok "JDK installed at $JDK_DIR"
}

export JAVA_HOME="$JDK_DIR"

# ---------------------------------------------------------------- Gradle

install_gradle() {
    if [[ -x "$GRADLE_DIR/bin/gradle" ]]; then
        ok "Gradle found at $GRADLE_DIR"
        return
    fi

    info "Installing Gradle $GRADLE_VERSION"
    mkdir -p "$TOOLS_DIR"
    curl -fsSL -o "$TOOLS_DIR/gradle.zip" \
        "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    command -v unzip >/dev/null 2>&1 || die "unzip is required (apt install unzip)"
    unzip -q "$TOOLS_DIR/gradle.zip" -d "$TOOLS_DIR"
    rm -f "$TOOLS_DIR/gradle.zip"
    ok "Gradle installed at $GRADLE_DIR"
}

# ---------------------------------------------------------------- Android SDK

install_android_sdk() {
    local cmdline="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

    if [[ ! -x "$cmdline" ]]; then
        info "Installing Android SDK command-line tools"
        mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
        curl -fsSL -o /tmp/cmdline-tools.zip \
            "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
        command -v unzip >/dev/null 2>&1 || die "unzip is required (apt install unzip)"
        unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-extract
        rm -rf /tmp/cmdline-tools.zip
        # sdkmanager refuses to run from a directory not named after the SDK it belongs to.
        mv /tmp/cmdline-tools-extract/cmdline-tools "$ANDROID_SDK_ROOT/cmdline-tools/latest"
        rm -rf /tmp/cmdline-tools-extract
    fi

    info "Accepting Android SDK licences"
    yes | "$cmdline" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null 2>&1 || true

    info "Installing platform + build tools"
    "$cmdline" --sdk_root="$ANDROID_SDK_ROOT" \
        "platform-tools" \
        "platforms;android-35" \
        "build-tools;35.0.0" >/dev/null

    ok "Android SDK ready at $ANDROID_SDK_ROOT"
}

# ---------------------------------------------------------------- build

main() {
    install_jdk
    install_gradle
    install_android_sdk

    export ANDROID_HOME="$ANDROID_SDK_ROOT"
    export ANDROID_SDK_ROOT
    export PATH="$GRADLE_DIR/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

    # local.properties is usually gitignored; regenerate it so builds work from a fresh clone.
    printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > "$PROJECT_DIR/local.properties"

    info "Building debug APK"
    ( cd "$PROJECT_DIR" && "$GRADLE_DIR/bin/gradle" assembleDebug --console=plain )

    local apk="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
    [[ -f "$apk" ]] || die "Build finished but APK not found at $apk"

    ok "APK built: $apk ($(du -h "$apk" | cut -f1))"

    if [[ "${1:-}" == "--install" ]]; then
        command -v adb >/dev/null 2>&1 || die "adb not on PATH"
        adb get-state >/dev/null 2>&1 || die "No device connected (adb devices)"
        info "Installing APK"
        adb install -r "$apk"
        ok "Installed"
    fi
}

main "$@"