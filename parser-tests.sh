#!/usr/bin/env bash
# 접근성 파서(BaeminScreenParser, CoupangScreenParser) + 진단 로그 가림(DiagnosticRedactor) 회귀 테스트. Android SDK 없이 JDK 만으로 실행 (javac + JUnit 4).
#   ./parser-tests.sh                 # 전부
#   ./parser-tests.sh BaeminScreenParserTest   # 클래스 하나
# 이 모듈을 Android 프로젝트에 넣으면 같은 테스트가 Gradle 단위 테스트(src/test/java)로도 돈다.
# JUnit jar: $JUNIT_JAR/$HAMCREST_JAR → ~/.m2 → Gradle 캐시 → Maven Central 다운로드(sha256 확인) 순.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAIN="$HERE/src/main/java/com/ridermeter/a11y"
TEST="$HERE/src/test/java/com/ridermeter/a11y"
# 순수 Java 클래스만 (android.* 의존 금지, 아래에서 검사)
PURE=(BaeminScreenParser.java CoupangScreenParser.java DiagnosticRedactor.java)

JUNIT_VER=4.13.2; JUNIT_SHA=8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3
HAMCREST_VER=1.3; HAMCREST_SHA=66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/ridermeter-parser-tests"

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

# find_jar <group/path> <artifact> <version> <sha256>
find_jar() {
    local gpath="$1" art="$2" ver="$3" sha="$4" f
    f="$HOME/.m2/repository/$gpath/$art/$ver/$art-$ver.jar"
    if [ -f "$f" ]; then echo "$f"; return; fi
    f="$(ls "$HOME"/.gradle/caches/modules-2/files-2.1/"${gpath//\//.}"/"$art"/"$ver"/*/"$art-$ver.jar" 2>/dev/null | head -n1 || true)"
    if [ -n "$f" ]; then echo "$f"; return; fi
    f="$CACHE/$art-$ver.jar"
    if [ ! -f "$f" ]; then
        mkdir -p "$CACHE"
        echo "downloading $art-$ver.jar from Maven Central" >&2
        curl -fsSL -o "$f.tmp" "https://repo1.maven.org/maven2/$gpath/$art/$ver/$art-$ver.jar"
        mv "$f.tmp" "$f"
    fi
    if [ "$(sha256 "$f")" != "$sha" ]; then echo "checksum mismatch: $f" >&2; rm -f "$f"; exit 1; fi
    echo "$f"
}

JUNIT="${JUNIT_JAR:-$(find_jar junit junit "$JUNIT_VER" "$JUNIT_SHA")}"
HAMCREST="${HAMCREST_JAR:-$(find_jar org/hamcrest hamcrest-core "$HAMCREST_VER" "$HAMCREST_SHA")}"

for f in "${PURE[@]}"; do
    if grep -qE '^import (android|androidx)\.' "$MAIN/$f"; then
        echo "FAIL: $f imports android.*, 여기 클래스는 순수 Java 여야 한다" >&2; exit 1
    fi
done

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# Git Bash(Windows) 는 classpath 구분자가 ';' 이고 경로를 Windows 형식으로 넘겨야 한다
SEP=':'; winpath() { echo "$1"; }
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';'; winpath() { cygpath -w "$1"; } ;; esac
CP="$(winpath "$JUNIT")$SEP$(winpath "$HAMCREST")"

SRCS=()
for f in "${PURE[@]}"; do SRCS+=("$(winpath "$MAIN/$f")"); done
for f in "$TEST"/*.java; do SRCS+=("$(winpath "$f")"); done

javac -encoding UTF-8 -nowarn -d "$(winpath "$OUT")" -cp "$CP" "${SRCS[@]}"

if [ $# -gt 0 ]; then
    CLASSES=("${@/#/com.ridermeter.a11y.}")
else
    CLASSES=()
    for f in "$TEST"/*Test.java; do CLASSES+=("com.ridermeter.a11y.$(basename "$f" .java)"); done
fi

java -Dfile.encoding=UTF-8 -cp "$(winpath "$OUT")$SEP$CP" org.junit.runner.JUnitCore "${CLASSES[@]}"
