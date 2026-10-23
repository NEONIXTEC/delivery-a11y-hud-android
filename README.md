# Delivery A11y HUD Engine for Android 🛵
> **배달 라이더 접근성 파서 & 플로팅 HUD 엔진**  
> *Read-Only Android Accessibility Service & Minimal Floating HUD Widget for Food Delivery Riders.*

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Platform](https://img.shields.io/badge/Platform-Android%2024%2B-green.svg)](https://developer.android.com)
[![Accessibility Read--Only](https://img.shields.io/badge/Accessibility-100%25%20Read--Only-brightgreen.svg)]()
[![No Touch / No Macro](https://img.shields.io/badge/Automation-0%25%20Touch%20%2F%20No%20Macro-orange.svg)]()

**Version**: RiderMeter 앱 1.18.5x (2026-10-24) 접근성 엔진과 동기화 / *Synced with the RiderMeter app 1.18.5x (2026-10-24) accessibility engine.*

---

## 📖 개요 (Overview)

이 프로젝트는 배달 라이더(기사님)들이 운행 중 복잡한 배달 앱 화면을 일일이 조작하거나 기억할 필요 없이, **배민커넥트** 및 **쿠팡이츠 배달파트너** 화면에서 발생하는 배달 완료 및 일일 실적을 **100% Read-Only(읽기 전용)**로 감지하여 실시간 **플로팅 알약(HUD) 위젯**으로 안전하게 표시해주는 안드로이드 오픈소스 엔진입니다.

[RiderMeter(라이더미터)](https://ridermeter.com) 앱의 접근성 엔진을 떼어낸 버전입니다. 화면 판정 코드(파서)는 앱과 똑같이 유지하고(주석 속 예시 가게명, 주소만 가짜 값으로 바꿈), 앱의 운행 기록이나 수익 장부 대신 `DeliveryEventListener` 콜백으로 결과를 넘깁니다.

This repository provides an open-source Android Accessibility Service and overlay HUD widget designed specifically for delivery riders. It transparently observes delivery completion events and official daily earnings from delivery partner apps (Baemin & Coupang Eats) in a strict **Read-Only** manner to provide instant visual feedback via a floating pill widget without interfering with safe driving. It is extracted from the RiderMeter app; the screen parsers are kept identical to the app, and results are delivered through the `DeliveryEventListener` callback instead of the app's trip and records storage.

---

## 🛡️ 4대 보안 및 안전 불변식 (Core Security Principles)

본 엔진은 구글 플레이 접근성 API 정책(Google Play Accessibility API Policy)을 지키도록 만들었고, 아래 네 가지를 코드로 보장합니다.

### 1. 0% Touch / 0% Macro (완전한 읽기 전용)
- 화면을 자동으로 터치하거나, 클릭하거나, 제스처를 시뮬레이션하는 코드가 단 1줄도 없습니다. (`dispatchGesture`, `performAction`, `performGlobalAction` 일절 미사용)
- 배차 수락/거절을 자동화하는 매크로 기능이 원천 배제되어 있습니다. 사용자가 직접 누른 버튼의 글자(`TYPE_VIEW_CLICKED`)는 읽기만 하고, 완료 모달이 닫혔는지 판단하는 데만 씁니다.

### 2. OS 차원의 패키지 격리 (OS-Level Package Isolation)
- `res/xml/accessibility_service_config.xml`에 오직 아래 2개 배달 플랫폼 공식 패키지만 명시적으로 선언되어 있습니다:
  - `com.woowahan.bros` (배민커넥트)
  - `com.coupang.mobile.eats.courier` (쿠팡이츠 배달파트너)
- 안드로이드 OS는 이 두 앱에서 난 접근성 이벤트만 본 서비스에 전달합니다. **금융 앱, 모바일 뱅킹, 카카오톡/메신저, 웹 브라우저 등 다른 앱의 이벤트는 오지 않습니다.**
- 코드에서도 한 번 더 거릅니다. 이벤트 패키지, 활성 창, 이벤트 소스, 팝업 창 목록을 모두 패키지 이름으로 확인하고, 두 앱이 아닌 창의 노드는 읽지 않습니다.

### 3. 화면 글자는 기기 안에서만 처리 (On-Device Only)
- **이 엔진에는 네트워크 코드가 없습니다.** 읽은 값은 `DeliveryEventListener` 콜백과 플로팅 HUD로만 넘기고, 파일로 저장하거나 기기 밖으로 보내지 않습니다.
- **배차 요청(콜) 화면은 읽지 않습니다.** `BaeminScreenParser.isOfferScreen`, `CoupangScreenParser.isOfferScreen`이 수락, 거절, 배차받기, 주문 수락 같은 콜 화면을 가장 먼저 걸러내고, 콜 화면으로 판정되면 그 자리에서 처리를 멈춰 금액 등 어떤 값도 꺼내지 않습니다.
- 꺼내는 값은 다음뿐입니다.
  - 배달이 끝난 뒤 완료 모달에 표시되는 **확정 배달료**
  - 배민 마이페이지, 쿠팡 '내 수입' 화면의 **당일 건수와 수입**
  - 배민 배달미션 줄(목표 건수, 보너스, 시간대)과 쿠팡 완료 모달의 깜짝미션 보너스
- 배민이 완료 모달을 띄우지 않고 "이미 전달완료된 배달입니다."로 끝나는 경우를 복구하려고 진행 목록의 주문번호, 배달료, 전달지 주소를 **메모리에만** 잠시 기억합니다. 이 값은 콜백으로 넘기지 않고(`orderId`는 항상 `null`), 영업일(06:00)이 바뀌면 지웁니다.
- 화면 글자 전체 덤프는 **디버그 빌드(`BuildConfig.DEBUG`)의 logcat에만** 찍힙니다. 릴리스 빌드에서는 찍지 않습니다.

#### RiderMeter 앱의 진단 로그 전송과 `DiagnosticRedactor`
- RiderMeter 앱에는 사용자가 직접 "진단 로그 전송"을 눌렀을 때만 동작하는 **선택 기능**이 있습니다. 이 엔진에는 그런 전송 기능이 없습니다.
- 앱은 보내기 전에 기기 안에서 [`DiagnosticRedactor`](src/main/java/com/ridermeter/a11y/DiagnosticRedactor.java)로 로그를 가립니다. 투명하게 공개하려고 같은 코드를 이 레포에 그대로 두었습니다 (엔진은 이 클래스를 부르지 않습니다).
- 가리는 규칙 (요약):
  - 전화번호: 010 휴대폰, 050x 안심번호, 070, 02 같은 지역번호, +82 형식 → `[전화번호]`
  - 주민등록번호 → `[주민번호]`
  - 주문번호: "주문번호 ..." 뒤 값, 대문자와 숫자가 섞인 6자 이상 코드 → `[주문번호]`, 8자리 이상 숫자 → `[번호]`
  - 주소: 도로명(로, 길 + 번호), 동, 호, 층, 번지, 시, 구, 군 + 동, 로, 아파트, 빌라, 오피스텔 등 단지 이름 → 주소가 섞인 줄은 통째로 `[주소]`
  - 요청사항: "요청사항:", "요청:", "메모:" 뒤 내용 → `[가림]`
  - 라이더 이름: "OOO 라이더님" → `[이름]`
  - 가게 지점 이름("OO점")과 "[매장명] 배달 완료"의 매장명 → `[가림]`
- 남기는 것은 금액(3,900원), 건수(13건), 파서가 아는 화면 단어(배달완료, 확인, 마이페이지 등), 숫자가 거의 없는 12자 이하 짧은 줄뿐이고, 나머지 줄은 모두 `[가림]`이 됩니다. 줄 번호는 남겨서 화면 구조만 보이게 합니다.

### 4. 엄격한 노드 경계 기반 순수 함수 파서 (Strict Node Parsing)
- 화면 판정은 `BaeminScreenParser`, `CoupangScreenParser` 두 순수 Java 클래스가 합니다 (`android.*` 의존 없음). 서비스는 노드 글자 수집, 화면 사이 상태, 이벤트 전달만 맡습니다.
- 콜 화면, 픽업 화면의 음식값(메뉴금액, 주문금액), 고객연결 팝업의 법적 고지문("배달 완료 시까지"), 가게 도착 확인 팝업(취소 버튼), 쿠팡 촬영 화면의 '배달 완료' 버튼은 완료로 잡지 않습니다.
- 예전의 화면 전환 추측(TRANSITION) 완료 판정은 오탐 때문에 전면 제거했습니다. 완료는 실제 완료 모달(또는 "이미 전달완료된 배달입니다." 누락 복구)로만 셉니다.
- 친구추천 이벤트 배너(10,000원 등)나 과거 날짜 데이터가 당일 실적으로 오인식되지 않도록 날짜별 노드 경계를 엄격히 격리합니다. 배민 마이페이지는 '어제' 탭이 선택돼 있거나 배달 중이면 동기화하지 않습니다.
- 실측 화면 덤프를 바탕으로 한 JDK 전용 단위 테스트로 검증합니다 (아래 테스트 절).

---

## 📣 접근성 사용 고지 (Accessibility use disclosure)

아래는 RiderMeter 앱이 Google Play에 제출한 접근성 API 사용 소명 문구 원문입니다. 이 엔진은 이 문구대로 동작합니다. 배달미션은 배민 배달미션 줄(목표 건수, 보너스, 시간대)과 쿠팡 완료 모달, 내 수입 화면의 미션 보너스를 말합니다. 이 엔진을 자기 앱에 넣는 경우, 그 앱도 권한을 켜기 전에 사용자에게 눈에 띄는 고지를 하고 동의를 받아야 합니다.

*Below is the accessibility API disclosure the RiderMeter app submitted to Google Play, verbatim. This engine behaves as described. "Delivery missions" means Baemin delivery-mission lines (target count, bonus, time slot) and the mission bonus on Coupang's completion modal and My Earnings screen. If you embed this engine in your own app, your app must also show a prominent disclosure and obtain consent before the user enables the service.*

**한국어**

> 라이더미터는 배달 라이더가 운행 중 휴대폰을 조작하지 않고도 오늘 번 금액과 건수를 확인하도록 돕는 앱입니다. 접근성 서비스는 배민커넥트(com.woowahan.bros)와 쿠팡이츠 배달파트너(com.coupang.mobile.eats.courier) 두 앱에만 한정되며, 배달이 끝난 뒤 화면에 표시되는 확정 배달료, 마이페이지의 당일 건수와 수입, 플랫폼이 보여주는 배달미션의 진행 건수와 보너스 금액만 읽어 플로팅 위젯에 표시합니다. 배차 요청(콜) 화면은 의도적으로 읽지 않고 걸러내며, 화면을 대신 터치하거나 수락, 거절 등 어떤 동작도 자동화하지 않습니다. 두 플랫폼은 라이더에게 실시간 정산 API 를 제공하지 않아 접근성 API 외에 이 기능을 구현할 방법이 없습니다. 읽은 화면 글자는 기기 안에서만 처리하며, 사용자가 직접 진단 로그 전송을 선택한 경우에만 주소, 전화번호 등을 지운 일부 글자가 서버로 전송됩니다. 권한을 켜기 전 앱 안에서 이 내용을 고지하고 동의를 받습니다.

**English**

> RiderMeter helps delivery riders see today's confirmed earnings and delivery count without touching their phone while riding. The accessibility service is limited to two packages (com.woowahan.bros and com.coupang.mobile.eats.courier). It reads only the confirmed delivery fee shown after a delivery is completed, the day's count and earnings on the rider's My Page, and the progress count and bonus amount of delivery missions shown by the platform, and shows them in a floating widget. Dispatch (call offer) screens are deliberately filtered out and never read. The service never performs gestures, clicks, accepts or rejects anything; it is strictly read-only. Neither platform offers riders a real-time earnings API, so the accessibility API is the only way to provide this. Screen text is processed on the device; only if the user explicitly chooses to send diagnostic logs is a redacted excerpt (addresses, phone numbers and order numbers removed) uploaded. The app shows a prominent in-app disclosure and asks for consent before the user enables the service.

---

## 🏗️ 아키텍처 (Architecture)

```text
┌────────────────────────────────────────────────────────┐
│               Android OS Accessibility API             │
│        (Target: com.woowahan.bros / Coupang Courier)   │
└──────────────────────────┬─────────────────────────────┘
                           │ AccessibilityEvent (STATE / CONTENT_CHANGED, VIEW_CLICKED)
                           ▼
┌────────────────────────────────────────────────────────┐
│            DeliveryAccessibilityService                │
│  - Filter: Whitelisted Packages only (event + windows) │
│  - Tree Traversal: Read-Only AccessibilityNodeInfo     │
│  - State: modal open / cooldown / in-flight / tab      │
│  - Debug-only logcat dump (BuildConfig.DEBUG)          │
└──────────────┬──────────────────────────┬──────────────┘
               │ List<String> node texts  │
               ▼                          ▼
 [BaeminScreenParser (Pure Java)] [CoupangScreenParser (Pure Java)]
  offer filter / completion modal  offer filter / completion modal
  my page / missions / order map   my earnings / in-flight / missions
               │                          │
               └──────────┬───────────────┘
                          │ Event Callback (DeliveryEventListener)
                          ▼
┌────────────────────────────────────────────────────────┐
│                FloatingPillService                     │
│  - TYPE_APPLICATION_OVERLAY Window Manager             │
│  - 208dp Minimal Floating HUD (Zero-Emoji Flat Design) │
│  - Real-time Rolling Display: Target vs Done / Earnings│
│  - Drag & Edge Snap Touch Gesture                      │
│  - Long-press Goal Setting Dialog                      │
└────────────────────────────────────────────────────────┘

 [DiagnosticRedactor (Pure Java)]  not called by the engine; published for transparency
```

---

## 📂 핵심 컴포넌트 (Components)

| 파일 (File) | 역할 (Role) |
|---|---|
| [`DeliveryAccessibilityService.java`](src/main/java/com/ridermeter/a11y/DeliveryAccessibilityService.java) | 접근성 코어 서비스. 노드 글자 수집, 화면 사이 상태(모달, 쿨다운, 배달 중, 어제 탭), 파서 호출, 이벤트 전달 |
| [`BaeminScreenParser.java`](src/main/java/com/ridermeter/a11y/BaeminScreenParser.java) | 배민 순수 파서. 콜 화면 필터, 완료 모달과 배달료, 마이페이지 실적, 배달미션 줄, 진행 목록 주문번호 정규식 |
| [`CoupangScreenParser.java`](src/main/java/com/ridermeter/a11y/CoupangScreenParser.java) | 쿠팡이츠 순수 파서. 콜 화면 필터, 배달 중 화면, 완료 모달, 깜짝미션, 내 수입 날짜별 건수/배달비/미션 분리 |
| [`DiagnosticRedactor.java`](src/main/java/com/ridermeter/a11y/DiagnosticRedactor.java) | RiderMeter 앱이 진단 로그를 보내기 전에 기기 안에서 쓰는 가림 규칙 (공개용, 엔진은 호출하지 않음) |
| [`FloatingPillService.java`](src/main/java/com/ridermeter/a11y/FloatingPillService.java) | 안드로이드 WindowManager 기반 플로팅 알약 HUD 오버레이 서비스 (`exported="false"`) |
| [`DeliveryEventListener.java`](src/main/java/com/ridermeter/a11y/DeliveryEventListener.java) | 배달 완료, 실적 동기화, 미션 이벤트 수신용 클린 인터페이스 |
| [`accessibility_service_config.xml`](src/main/res/xml/accessibility_service_config.xml) | OS 레벨 패키지 화이트리스트 격리 설정 |
| [`parser-tests.sh`](parser-tests.sh) | Android SDK 없이 JDK만으로 파서와 가림 규칙 테스트를 돌리는 실행기 |

---

## 🧪 단위 테스트 실행 (Running Tests)

파서와 가림 규칙은 순수 Java라서 Android SDK 없이 JDK(`javac`, `java`)와 `curl`만 있으면 바로 테스트할 수 있습니다:

```bash
./parser-tests.sh                          # 전부
./parser-tests.sh BaeminScreenParserTest   # 클래스 하나
```

- JUnit 4.13.2와 hamcrest-core 1.3은 `~/.m2`, Gradle 캐시, `~/.cache/ridermeter-parser-tests` 순으로 찾고, 없으면 Maven Central에서 받아 sha256을 확인합니다 (`JUNIT_JAR`, `HAMCREST_JAR` 환경변수로 직접 지정 가능).
- 실행기는 세 파서 클래스가 `android.*`를 import 하지 않는지도 검사합니다.
- 이 모듈을 Android 프로젝트에 라이브러리로 넣으면 같은 테스트가 Gradle 단위 테스트(`src/test/java`)로도 돕니다.

| 테스트 (Test) | 개수 | 내용 |
|---|---|---|
| `BaeminScreenParserTest` | 42 (KNOWN ISSUE 2개 `@Ignore`) | 콜 화면 필터, 완료 모달(묶음, 단건, 금액 하한/상한, 메뉴금액 무시, 고객연결 고지문, 가게 도착 팝업), 마이페이지, 배달미션, 주문번호 정규식 |
| `CoupangScreenFlowTest` | 19 (KNOWN ISSUE 1개 `@Ignore`) | 대기, 콜, 매장 도착, 픽업, 촬영, 완료 모달, 깜짝미션, 내 수입 흐름 |
| `CoupangScreenParserTest` | 6 | 내 수입(친구초대 배너 방어), 완료 모달, 촬영 화면, 배달 내역 |
| `DiagnosticRedactorTest` | 19 | 금액, 건수, 화면 단어는 남고 주소, 전화번호, 주문번호, 요청사항, 이름은 가려지는지 |

현재 결과: **86개 중 83개 통과, 3개는 `@Ignore`** (바라는 동작을 적어 둔 KNOWN ISSUE로, 앱도 같은 상태입니다).

테스트 픽스처(`ScreenFixtures.java`)는 실측 화면 덤프의 노드 경계, 글자 모양, 금액, 건수를 그대로 두고, 가게명, 주소, 사람 이름, 소속, 주문번호는 공개용 가짜 값으로 바꾼 것입니다.

---

## 📄 라이선스 (License)

본 프로젝트는 **MIT License** 하에 자유롭게 사용, 수정, 배포할 수 있습니다. 자세한 내용은 [LICENSE](LICENSE) 파일을 참조하세요.

```text
Copyright (c) 2026 NEONIX
Licensed under the MIT License.
```
