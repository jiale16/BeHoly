#!/usr/bin/env bash
# BeHoly 本地编译脚本（绕过坏掉的 gradle wrapper）
#
# 背景：项目 gradle-wrapper.properties 的 distributionUrl 指向不存在的
#       file:///C:/android/gradle-8.3-all.zip，导致 ./gradlew 不可用。
#       本脚本直接调用本机已下载的 Gradle 8.11.1 二进制 + JDK 17 编译。
#
# 用法（在 Git Bash 中，cd 到 BeHoly 目录后）：
#   ./build.sh              # 默认编译 release（assembleRelease）
#   ./build.sh debug        # 编译 debug（assembleDebug）
#   ./build.sh clean        # 清理 build 目录
#   ./build.sh assembleRelease --rerun-tasks   # 带额外 gradle 参数
#
# 产物：
#   release -> app/build/outputs/apk/release/app-release.apk
#   debug   -> app/build/outputs/apk/debug/app-debug.apk

set -euo pipefail

# 本机 JDK 17（AGP 8.2+ 强制要求）
export JAVA_HOME="/c/Users/TJL/.jdks/ms-17.0.18"
# 本机 Android SDK（local.properties 的 sdk.dir 已指向此处）
export ANDROID_HOME="/c/Users/TJL/AndroidSdk"

# 切到脚本所在目录（即 BeHoly 工程根）
cd "$(dirname "$0")"

# 本机 Gradle 8.11.1 二进制（绕过坏 wrapper）
GRADLE="/c/Users/TJL/.gradle/wrapper/dists/gradle-8.11.1-bin/eac4u065zwes5phgltp5f9b9e/gradle-8.11.1/bin/gradle"

# 默认任务：release
TASK="${1:-assembleRelease}"
shift || true

exec "$GRADLE" "$TASK" --no-daemon "$@"
