package com.ridermeter.a11y;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [DeliveryAccessibilityService]
 * 배민커넥트 및 쿠팡이츠 배달파트너 전용 실시간 배달 완료/수익 감지 서비스.
 * 화면 판정은 순수 Java 파서(BaeminScreenParser, CoupangScreenParser)가 하고, 이 서비스는
 * 노드 글자 수집, 화면 사이 상태(모달 열림, 쿨다운, 배달 중, 어제 탭), 이벤트 전달만 맡는다.
 * 판정 순서와 가드는 RiderMeter 앱의 같은 이름 서비스와 같다.
 *
 * 보안 및 안전 철칙 (Security Principles):
 * 1. 0% Touch / 0% Macro:
 *    어떠한 자동 클릭, 제스처, 터치 조작 코드도 일절 포함하지 않는 100% Read-Only 서비스입니다.
 *    (dispatchGesture, performAction, performGlobalAction 미사용)
 * 2. OS-Level Package Isolation:
 *    accessibility_service_config.xml 에서 오직 배민(com.woowahan.bros)과
 *    쿠팡(com.coupang.mobile.eats.courier) 2개 앱만 지정하고, 코드에서도 패키지를 한 번 더 확인합니다.
 * 3. 배차 요청(콜) 화면은 읽지 않는다:
 *    콜 화면으로 판정되면 그 자리에서 처리를 멈추고 금액 등 어떤 값도 꺼내지 않습니다.
 *    읽는 값은 배달완료 확정 배달료, 마이페이지/내 수입의 당일 건수와 수입, 배달미션 줄뿐입니다.
 * 4. 기기 밖으로 보내지 않는다:
 *    이 엔진에는 네트워크 코드가 없습니다. 읽은 값은 DeliveryEventListener 와 플로팅 HUD 로만 넘깁니다.
 *    화면 글자 전체 덤프는 디버그 빌드의 logcat 에만 찍고, 파일로 저장하지 않습니다.
 */
public class DeliveryAccessibilityService extends AccessibilityService {
    private static final String TAG = "DeliveryA11y";

    public static final String PKG_BAEMIN = "com.woowahan.bros";
    public static final String PKG_COUPANG = "com.coupang.mobile.eats.courier";

    // 화면 글자 덤프(주소, 요청사항이 섞일 수 있다)는 디버그 빌드에서만 logcat 에 찍는다.
    private static final boolean DEBUG_DUMP = BuildConfig.DEBUG;

    // 중복 방지 상태 가드
    private static boolean isModalOpen = false;
    private static long lastTriggerTime = 0;
    private static int lastAmount = 0;
    private static final long COOLDOWN_MS = 3000; // 배달 완료 간 3초 디바운스
    // 배달료 인정 범위(라벨 붙은 금액 500원, 단독 금액 1,500원, 상한 20,000원)는 BaeminScreenParser 에 있다.
    // 예전의 화면 전환 추측(TRANSITION) 완료 판정은 오탐 때문에 앱에서 전면 제거되었고 여기에도 없다.

    // 마이페이지 '어제' 탭 클릭 상태 추적 (어제 실적으로 오동기화되는 현상 차단)
    private static boolean sIsYesterdayTabActive = false;

    // 배민 실시간 배달 진행 상태 추적
    private static volatile boolean sIsDeliveryInProgress = false;
    private static volatile long sLastDeliveryActiveTime = 0L;

    // 쿠팡 실시간 배달 진행 상태 추적
    private static volatile boolean sIsCoupangDeliveryInProgress = false;
    private static volatile long sLastCoupangDeliveryActiveTime = 0L;

    // 배민 마이페이지 동기화 디바운스
    private static int sLastSyncedCount = -1;
    private static long sLastSyncedEarnings = -1;
    private static long sLastSyncTime = 0L;

    // 쿠팡 '내 수입' 동기화 디바운스
    private static int sLastCoupangSyncedCount = -1;
    private static long sLastCoupangSyncedEarnings = -1;
    private static long sLastCoupangSyncTime = 0L;

    // 배민 미션 내역의 완료 줄은 화면을 열 때마다 다시 읽힌다. 영업일마다 같은 미션은 한 번만 알린다.
    // (슬롯 이름 "오늘 17:30 ~ 20:30"은 날마다 같은 문자열이라 영업일이 바뀌면 비운다)
    private static final Set<String> sCompletedMissionKeys = new HashSet<>();
    private static String sMissionBizDate = "";
    private static final Pattern SLOT_TIME = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*~\\s*(\\d{1,2}):(\\d{2})");

    // ---- 완료 모달 누락 복구 ----
    // 전달완료 탭 후 "일시적인 오류가 발생했어요"를 거쳐 "이미 전달완료된 배달입니다."로 끝나면
    // 배민이 완료 모달을 아예 안 띄워 1건이 통째로 빠진다.
    // 진행 목록에서 주문번호와 배달료, 전달지 주소와 주문번호를 메모리에만 기억해 두고, 촬영 화면의 주소로 그 주문을 특정한다.
    // 이 값들은 밖으로 넘기지 않고 영업일이 바뀌면 지운다.
    // (주문번호 정규식: BaeminScreenParser.ORDER_STORE_PATTERN / ORDER_DROP_PATTERN)
    private static final Map<String, Integer> sOrderFee = new HashMap<>();
    private static final Map<String, String> sDropAddrOrder = new HashMap<>();
    private static final Set<String> sCompletedOrders = new HashSet<>();
    private static String sCurrentDropOrder = null;
    private static String sOrderMapBizDate = "";

    // 외부 이벤트 리스너
    private static volatile DeliveryEventListener sListener;

    public static void setDeliveryEventListener(DeliveryEventListener listener) {
        sListener = listener;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 접근성 콜백에서 예외가 새면 호스트 앱 프로세스가 통째로 죽는다. 껍데기에서 전부 잡는다.
        try {
            handleEvent(event);
        } catch (Throwable t) {
            Log.e(TAG, "onAccessibilityEvent failed", t);
        }
    }

    private void handleEvent(AccessibilityEvent event) {
        if (event == null) return;
        String pkg = (event.getPackageName() != null) ? event.getPackageName().toString() : "";
        if (!PKG_BAEMIN.equals(pkg) && !PKG_COUPANG.equals(pkg)) {
            return;
        }

        int eventType = event.getEventType();
        // getSource() 는 부를 때마다 새 노드(null 가능)라 한 번만 받는다
        AccessibilityNodeInfo source = event.getSource();

        // 사용자가 화면 내 버튼을 직접 누른 순간 (읽기만 한다. 이 서비스는 아무것도 누르지 않는다)
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            handleClick(event, source, pkg);
            return;
        }

        // TYPE_WINDOW_STATE_CHANGED 시 sIsYesterdayTabActive를 임의로 false로 리셋하지 않음 (배민 앱이 '어제' 탭을 기억하고 열었을 때 오늘 실적 오염 방지)

        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }

        // 활성 창과 이벤트 소스도 패키지를 다시 확인한다 (홈 화면 등 다른 앱 노드가 섞이지 않게)
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
        if (activeRoot != null) {
            CharSequence activePkg = activeRoot.getPackageName();
            if (activePkg != null && pkg.contentEquals(activePkg)) {
                roots.add(activeRoot);
            }
        }
        if (roots.isEmpty() && source != null) {
            CharSequence srcPkg = source.getPackageName();
            if (srcPkg != null && pkg.contentEquals(srcPkg)) {
                roots.add(source);
            }
        }

        // 같은 패키지 소속 다이얼로그/팝업 윈도우까지 추가 수집 (다른 앱 창은 건너뛴다)
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo win : windows) {
                    AccessibilityNodeInfo winRoot = win.getRoot();
                    if (winRoot != null) {
                        CharSequence winPkg = winRoot.getPackageName();
                        if (winPkg != null && pkg.contentEquals(winPkg)) {
                            boolean exists = false;
                            for (AccessibilityNodeInfo r : roots) {
                                if (r.equals(winRoot)) {
                                    exists = true;
                                    break;
                                }
                            }
                            if (!exists) {
                                roots.add(winRoot);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        if (roots.isEmpty()) return;

        try {
            List<String> textNodes = new ArrayList<>();
            for (AccessibilityNodeInfo r : roots) {
                collectTexts(r, textNodes);
            }

            if (PKG_BAEMIN.equals(pkg)) {
                handleBaemin(textNodes);
            } else {
                handleCoupang(textNodes);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing accessibility event", e);
        }
    }

    private void handleClick(AccessibilityEvent event, AccessibilityNodeInfo source, String pkg) {
        String clickedText = null;
        List<CharSequence> evTexts = event.getText();
        if (evTexts != null && !evTexts.isEmpty() && evTexts.get(0) != null) {
            clickedText = evTexts.get(0).toString();
        }
        if (clickedText == null && event.getContentDescription() != null) {
            clickedText = event.getContentDescription().toString();
        }
        if (clickedText == null && source != null) {
            CharSequence sc = source.getText();
            if (sc != null) clickedText = sc.toString();
        }

        if (DEBUG_DUMP) {
            String clickInfo = "CLICK [" + (PKG_BAEMIN.equals(pkg) ? "BAEMIN" : "COUPANG") + "]: text='" + clickedText + "' cls=" + event.getClassName();
            List<String> curTexts = new ArrayList<>();
            AccessibilityNodeInfo curRoot = getRootInActiveWindow();
            if (curRoot == null) curRoot = source;
            CharSequence curPkg = (curRoot != null) ? curRoot.getPackageName() : null;
            if (curPkg != null && pkg.contentEquals(curPkg)) collectTexts(curRoot, curTexts);
            debugDump(PKG_BAEMIN.equals(pkg), "USER_CLICK: " + clickInfo, curTexts, "User clicked");
        }

        if (clickedText == null) return;
        String ns = clickedText.replace(" ", "");

        // 배차 제안(수락/거절) 클릭 시 배달 완료 오인식 원천 차단
        if (ns.contains("수락") || ns.contains("거절") || ns.equals("배차받기")) {
            if (isModalOpen) isModalOpen = false;
            return;
        }

        if (ns.equals("배달이어서하기") || ns.equals("배달계속하기") || ns.equals("이어서배달하기") ||
            ns.equals("이어서진행하기") || ns.equals("확인") || ns.equals("닫기") || ns.contains("신규배차켜기")) {
            // 완료 모달 계속하기/확인 버튼 → 모달 닫힘 상태 전환
            isModalOpen = false;
        } else if (clickedText.equals("어제")) {
            // 마이페이지 '어제' 탭 → 동기화 비활성화
            sIsYesterdayTabActive = true;
        } else if (clickedText.equals("오늘")) {
            sIsYesterdayTabActive = false;
        }
    }

    private boolean isNodeOrParentSelected(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isSelected() || node.isChecked()) return true;
        CharSequence cd = node.getContentDescription();
        if (cd != null && cd.toString().contains("선택됨")) return true;
        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null) {
            if (parent.isSelected() || parent.isChecked()) return true;
            CharSequence pcd = parent.getContentDescription();
            if (pcd != null && pcd.toString().contains("선택됨")) return true;
        }
        return false;
    }

    private void collectTexts(AccessibilityNodeInfo node, List<String> list) {
        if (node == null) return;

        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            String s = text.toString().trim();
            if (s.equals("어제") && isNodeOrParentSelected(node)) {
                list.add("__YESTERDAY_SELECTED__");
            } else if (s.equals("오늘") && isNodeOrParentSelected(node)) {
                list.add("__TODAY_SELECTED__");
            }
            list.add(s);
        }

        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) {
            String d = desc.toString().trim();
            if (d.contains("어제") && isNodeOrParentSelected(node)) {
                list.add("__YESTERDAY_SELECTED__");
            } else if (d.contains("오늘") && isNodeOrParentSelected(node)) {
                list.add("__TODAY_SELECTED__");
            }
            list.add(d);
        }

        CharSequence hint = node.getHintText();
        if (hint != null && hint.length() > 0) {
            list.add(hint.toString().trim());
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectTexts(child, list);
            }
        }
    }

    // =================================================================
    // 배민
    // =================================================================

    private void handleBaemin(List<String> texts) {
        // 0. 배차 제안(콜) 화면: 최우선으로 걸러내고 아무 값도 읽지 않는다
        //    (수락, 거절, 배차 제안 중에는 배달 완료 및 금액 가산 절대 불가)
        if (BaeminScreenParser.isOfferScreen(texts)) {
            if (isModalOpen) isModalOpen = false;
            return;
        }

        // 0-1. 주문 학습 (진행 목록) + 촬영 화면 주소로 현재 전달 주문 특정 + 모달 누락 복구
        if (recoverMissedCompletion(texts)) return;

        // 0-2. 배민 실시간 주행 상태 추적 (배달 수행 중 vs 대기 상태)
        boolean hasIdleText = BaeminScreenParser.hasIdleText(texts);
        boolean hasDeliveryCard = BaeminScreenParser.hasDeliveryCard(texts);

        if (hasIdleText) {
            sIsDeliveryInProgress = false;
            sLastDeliveryActiveTime = 0L;
        } else if (hasDeliveryCard) {
            sIsDeliveryInProgress = true;
            sLastDeliveryActiveTime = System.currentTimeMillis();
        }

        // A-1. 피크타임 배달미션 (대기 화면 하단 시트, 미션 내역)
        if (BaeminScreenParser.hasMissionContext(texts)) {
            handleBaeminMissions(texts);
        }

        // A. 마이페이지(사람모양 아이콘) 오늘 공식 실적
        boolean hasMyPageTitle = texts.contains("마이페이지");

        // '어제' 탭 클릭 플래그는 마이페이지를 떠나면 버린다 (배민은 마이페이지에 다시 들어오면 '오늘'로 연다).
        // 이 리셋이 없으면 플래그가 영구 고착돼 마이페이지 동기화가 아예 멈춘다.
        if (!hasMyPageTitle && sIsYesterdayTabActive) {
            sIsYesterdayTabActive = false;
        }

        if (BaeminScreenParser.isMyPageStats(texts)) {
            // [어제 탭 방어] '어제'가 선택된 게 확인될 때만 막는다.
            // "오늘 선택이 확인 안 되면 막기"는 금지: 화면에 '어제' 탭 글자는 늘 있고 선택 상태 노출은 보장이 없어
            // 정상 오늘 화면까지 전부 조용히 막힌다.
            boolean isExplicitlyToday = texts.contains("__TODAY_SELECTED__");
            boolean isYesterdaySelected = texts.contains("__YESTERDAY_SELECTED__") || (sIsYesterdayTabActive && !isExplicitlyToday);
            if (isYesterdaySelected) {
                return;
            }
            sIsYesterdayTabActive = false;

            // 배달 수행 중(가게도착/픽업완료/고객이동) 마이페이지 진입 시,
            // 배민 서버가 미완료 배달을 미리 완료건수 및 수입에 조기 반영하므로 동기화 차단
            long now = System.currentTimeMillis();
            boolean isCurrentlyDelivering = sIsDeliveryInProgress || (now - sLastDeliveryActiveTime < 180000);
            if (isCurrentlyDelivering) {
                Log.d(TAG, ">>> [배민 마이페이지] 배달 수행 중 진입, 조기 누적 방지를 위해 동기화 차단");
                return;
            }

            BaeminScreenParser.MyPageResult myPage = BaeminScreenParser.parseMyPage(texts);
            // [안정화 가드] 0건/0원은 진입 직후 로딩 깜빡임이므로 차단, 둘 다 읽혔을 때만 동기화
            if (myPage.count > 0 && myPage.earnings > 0) {
                debugDump(true, ">>> A11Y_SYNC_MYPAGE <<<", texts, "MyPage parsed: " + myPage.count + "건 / " + myPage.earnings + "원");
                handleBaeminSync(myPage.count, myPage.earnings);
            }
            return;
        }

        // B. 배달완료 모달 감지
        BaeminScreenParser.CompletionModalResult modal = BaeminScreenParser.evaluateCompletionModal(texts);
        boolean isBaeminMainScreen = BaeminScreenParser.isMainScreen(texts, hasIdleText, hasDeliveryCard);

        if (modal.isModal) {
            long now = System.currentTimeMillis();
            int finalAmount = modal.amount;   // 범위 밖, 못 읽음 → 3,500원 (BaeminScreenParser.DEFAULT_FEE)

            // 중복 트리거 방어:
            // 1) 모달이 이미 열려 있는 상태면 재트리거 금지 (isModalOpen 유지)
            // 2) 동일 금액(동일 모달 재진입 의심): 최소 60초 쿨다운
            // 3) 다른 금액: 최소 15초 쿨다운
            long minCooldown = (finalAmount == lastAmount) ? 60000L : 15000L;

            if (!isModalOpen && (now - lastTriggerTime >= minCooldown)) {
                isModalOpen = true;
                lastTriggerTime = now;
                lastAmount = finalAmount;

                if (sCurrentDropOrder != null) sCompletedOrders.add(sCurrentDropOrder);
                Log.i(TAG, ">>> [배민 배달완료 모달 감지] 배달료: " + finalAmount + "원 (묶음 여부: " + modal.hasContinueButton + ")");
                debugDump(true, ">>> MODAL_DELIVERY_COMPLETE <<<", texts, "Parsed amount: " + finalAmount + "원 (bundle: " + modal.hasContinueButton + ")");
                dispatchDeliveryComplete(finalAmount, "baemin");
            }
            return;
        } else {
            // 외부 앱(홈 화면 등)으로 빠져나갔을 때는 모달이 닫힌 게 아니므로 isModalOpen을 풀지 않는다.
            // 배민 내부에서 완료 모달이 사라지고 메인/대기/진행 화면이 명백히 돌아왔을 때만 모달 닫힘으로 전환
            if (isModalOpen && isBaeminMainScreen) {
                isModalOpen = false;
            }
        }

        if (DEBUG_DUMP) {
            String evalSummary = String.format(Locale.KOREA,
                "isModal=%b (cont=%b, confirm=%b, cancel=%b, title=%b, amt=%d) | idle=%b | card=%b",
                modal.isModal, modal.hasContinueButton, modal.hasConfirmButton, modal.hasCancelButton, modal.hasTitle, modal.parsedAmount,
                hasIdleText, hasDeliveryCard);
            debugDump(true, "BAEMIN_UI_STATE", texts, evalSummary);
        }
    }

    private void handleBaeminMissions(List<String> texts) {
        // 어제 미션 상태가 남은 채로 오늘 같은 이름의 슬롯을 읽으면 안 되므로 영업일 롤오버부터
        String today = getBusinessDate();
        if (!today.equals(sMissionBizDate)) {
            sCompletedMissionKeys.clear();
            sMissionBizDate = today;
        }
        String bizDayLabel = getBusinessMonthDayLabel();
        // 홈 하단 시트(진행/예정 배달미션)에는 미션 카드가 여러 개 한꺼번에 뜬다. 카드마다 따로 알린다.
        boolean onHomeSheet = BaeminScreenParser.isMissionHomeSheet(texts);
        for (String rawText : texts) {
            BaeminScreenParser.MissionLine ml = BaeminScreenParser.parseMissionLine(rawText, bizDayLabel);
            if (ml == null) continue;
            if (ml.kind == BaeminScreenParser.MissionLine.PROGRESS) {
                // 1. 진행 중 미션: "0, /13, 13건, 11,000원, 오늘 17:30 ~ 20:30"
                // 미션 내역 화면의 지난 슬롯(오늘 14:00~17:00 0/11 등)을 진행 미션으로 오인 금지
                if (!isMissionSlotActive(ml.slot)) continue;
                notifyMission(ml.cur, ml.target, ml.bonus, ml.slot, true, false);
            } else if (ml.kind == BaeminScreenParser.MissionLine.SCHEDULED) {
                // 1-1. 예정 미션 → 등록만 (시작되면 배달 건수로 센다)
                notifyMission(0, ml.target, ml.bonus, ml.slot, false, false);
            } else if (onHomeSheet) {
                // 2. 진행 숫자 없는 줄이 홈 시트에 있으면 진행 중 카드 → 등록만
                notifyMission(0, ml.target, ml.bonus, ml.slot, false, false);
            } else {
                // 그 밖(미션 내역)이면 배민이 완료로 보여준 줄
                notifyMission(ml.target, ml.target, ml.bonus, ml.slot, true, true);
            }
        }
    }

    private void notifyMission(int cur, int target, long bonus, String slot, boolean inProgress, boolean completed) {
        DeliveryEventListener l = sListener;
        if (l != null) {
            try { l.onMissionObserved("baemin", cur, target, bonus, slot, inProgress, completed); } catch (Exception e) { Log.e(TAG, "listener failed", e); }
        }
        if (completed) {
            String key = slot + "_" + target + "_" + bonus;
            if (sCompletedMissionKeys.add(key)) {
                Log.i(TAG, ">>> [배민 미션 완료 확인] " + target + "건 / " + bonus + "원 (" + slot + ")");
                if (l != null) {
                    try { l.onMissionCompleted("baemin", target, bonus, slot); } catch (Exception e) { Log.e(TAG, "listener failed", e); }
                }
            }
        }
    }

    /** 반환 true = 누락 완료를 복구 디스패치했다(이번 이벤트 처리 끝). */
    private boolean recoverMissedCompletion(List<String> texts) {
        String today = getBusinessDate();
        if (!today.equals(sOrderMapBizDate)) {
            sOrderFee.clear();
            sDropAddrOrder.clear();
            sCompletedOrders.clear();
            sCurrentDropOrder = null;
            sOrderMapBizDate = today;
        }
        boolean cameraScreen = false;
        boolean alreadyDone = false;
        for (String t : texts) {
            Matcher ms = BaeminScreenParser.ORDER_STORE_PATTERN.matcher(t);
            if (ms.find()) {
                try { sOrderFee.put(ms.group(1), Integer.parseInt(ms.group(2).replace(",", ""))); } catch (NumberFormatException ignored) {}
            }
            Matcher md = BaeminScreenParser.ORDER_DROP_PATTERN.matcher(t);
            if (md.find()) sDropAddrOrder.put(md.group(1).trim(), md.group(2));
            if (t.contains("상품과 전달지가") || t.contains("촬영이 어려우신가요")) cameraScreen = true;
            if (t.replace(" ", "").contains("이미전달완료된배달")) alreadyDone = true;
        }
        if (sOrderFee.size() > 300) sOrderFee.clear();           // 하루치로 충분, 무한 증식 방지
        if (sDropAddrOrder.size() > 300) sDropAddrOrder.clear();

        // 전달 촬영 화면 첫 줄 = 전달지 주소 → 지금 전달 중인 주문
        if (cameraScreen) {
            for (String t : texts) {
                String oid = sDropAddrOrder.get(t.trim());
                if (oid != null) { sCurrentDropOrder = oid; break; }
            }
        }

        if (!alreadyDone || sCurrentDropOrder == null || sCompletedOrders.contains(sCurrentDropOrder)) return false;
        long now = System.currentTimeMillis();
        if (now - lastTriggerTime < COOLDOWN_MS) return false;     // 정상 모달 직후 중복 탭은 무시
        Integer fee = sOrderFee.get(sCurrentDropOrder);
        int amount = (fee != null && fee >= BaeminScreenParser.MIN_LABELED_FEE && fee <= BaeminScreenParser.MAX_FEE) ? fee : BaeminScreenParser.DEFAULT_FEE;
        sCompletedOrders.add(sCurrentDropOrder);
        lastTriggerTime = now;
        Log.i(TAG, ">>> [배민 완료 모달 누락 복구] 배달료: " + amount + "원");
        debugDump(true, ">>> MODAL_MISSING_RECOVERED <<<", texts, "amount=" + amount);
        dispatchDeliveryComplete(amount, "baemin");
        return true;
    }

    private void handleBaeminSync(int count, long earnings) {
        long now = System.currentTimeMillis();
        if (sLastSyncedCount == count && sLastSyncedEarnings == earnings && (now - sLastSyncTime < 4000)) {
            return;
        }
        sLastSyncedCount = count;
        sLastSyncedEarnings = earnings;
        sLastSyncTime = now;

        Log.i(TAG, ">>> [배민 마이페이지] 오늘 공식 실적 동기화: " + count + "건 / " + earnings + "원");
        notifySynced("baemin", count, earnings);
    }

    // =================================================================
    // 쿠팡이츠
    // =================================================================

    private void handleCoupang(List<String> texts) {
        // 0. 배차 제안(콜) 화면: 최우선으로 걸러내고 아무 값도 읽지 않는다
        if (CoupangScreenParser.isOfferScreen(texts)) {
            if (isModalOpen) isModalOpen = false;
            return;
        }

        // 0-1. '내 수입' 화면 공식 실적 동기화 (영업일 06:00 ~ 익일 05:59 기준 날짜 행만)
        Calendar bizCal = Calendar.getInstance();
        bizCal.add(Calendar.HOUR_OF_DAY, -6);
        int bizMonth = bizCal.get(Calendar.MONTH) + 1;
        int bizDay = bizCal.get(Calendar.DAY_OF_MONTH);
        String bizDatePat = String.format(Locale.US, "%02d/%02d", bizMonth, bizDay);

        CoupangScreenParser.EarningsSyncResult syncResult = CoupangScreenParser.parseEarningsSync(texts, bizDatePat);

        if (syncResult.valid) {
            long now = System.currentTimeMillis();
            // 배달 중 플래그가 모달 누락으로 영구 고착되는 것 방어 (최근 활성 후 90초 경과 시 자동 해제)
            boolean isRecentInFlight = (sLastCoupangDeliveryActiveTime > 0) && (now - sLastCoupangDeliveryActiveTime < 90000);
            if (sIsCoupangDeliveryInProgress && isRecentInFlight) {
                Log.d(TAG, ">>> [쿠팡 내 수입] 배달 수행 중 진입, 조기 누적 차단");
                return;
            } else {
                sIsCoupangDeliveryInProgress = false;
                sLastCoupangDeliveryActiveTime = 0L;
            }

            debugDump(false, ">>> COUPANG_EARNINGS_SYNC <<<", texts, "Count: " + syncResult.count + ", Fee: " + syncResult.fee + ", Mission: " + syncResult.missionBonus + ", Total: " + syncResult.total);
            handleCoupangSync(syncResult.count, syncResult.fee, syncResult.missionBonus);
            return;
        }

        // 1. 배달완료 모달 감지 (금액을 못 찾으면 0원, fail-closed)
        CoupangScreenParser.CompletionModalResult modalResult = CoupangScreenParser.parseCompletionModal(texts);

        if (modalResult.isModal) {
            long now = System.currentTimeMillis();
            if (!isModalOpen && (now - lastTriggerTime >= COOLDOWN_MS)) {
                isModalOpen = true;
                lastTriggerTime = now;
                sIsCoupangDeliveryInProgress = false;
                sLastCoupangDeliveryActiveTime = 0L;

                int finalAmount = modalResult.amount;
                Log.i(TAG, ">>> [쿠팡이츠 배달완료 모달 감지] 배달료: " + finalAmount + "원");
                debugDump(false, ">>> MODAL_DELIVERY_COMPLETE <<<", texts, "Parsed amount: " + finalAmount + "원");
                dispatchDeliveryComplete(finalAmount, "coupang");

                // 깜짝미션: "진행한 미션 1개 / 성공! 1만 5천원 보너스 / 3건 배달" → 그 자리에서 알린다
                CoupangScreenParser.ModalMissionResult mm = CoupangScreenParser.parseModalMission(texts);
                if (mm.success) {
                    Log.i(TAG, ">>> [쿠팡 깜짝미션 성공] bonus=" + mm.bonus + "원, target=" + mm.target + "건");
                    DeliveryEventListener l = sListener;
                    if (l != null) {
                        try { l.onMissionCompleted("coupang", mm.target, mm.bonus, null); } catch (Exception e) { Log.e(TAG, "listener failed", e); }
                    }
                } else if (mm.remaining > 0) {
                    Log.d(TAG, ">>> [쿠팡 깜짝미션 진행] " + mm.remaining + "건 남음, bonus=" + mm.bonus + "원");
                }
            }
            return;
        } else {
            if (isModalOpen) {
                isModalOpen = false;
            }
        }

        // 2. 픽업 및 배달 수행 중 상태 추적 (음식값 13,500원 등 오탐 차단, 내 수입 조기 동기화 차단용)
        boolean hasCoupangInFlight = CoupangScreenParser.isInFlightScreen(texts);
        if (hasCoupangInFlight) {
            sIsCoupangDeliveryInProgress = true;
            sLastCoupangDeliveryActiveTime = System.currentTimeMillis();
            if (isModalOpen) isModalOpen = false;
        }

        if (DEBUG_DUMP) {
            String evalSummary = String.format(Locale.KOREA,
                "isModal=%b (amt=%d) | inFlight=%b", modalResult.isModal, modalResult.amount, hasCoupangInFlight);
            debugDump(false, "COUPANG_UI_STATE", texts, evalSummary);
        }
    }

    private void handleCoupangSync(int count, long fee, long missionBonus) {
        long now = System.currentTimeMillis();
        long total = fee + missionBonus;
        if (sLastCoupangSyncedCount == count && sLastCoupangSyncedEarnings == total && (now - sLastCoupangSyncTime < 4000)) {
            return;
        }
        sLastCoupangSyncedCount = count;
        sLastCoupangSyncedEarnings = total;
        sLastCoupangSyncTime = now;

        Log.i(TAG, ">>> [쿠팡 내 수입] 오늘 공식 실적 동기화: " + count + "건 / 배달료 " + fee + "원 / 미션 " + missionBonus + "원 (총 " + total + "원)");
        notifySynced("coupang", count, total);
    }

    // =================================================================
    // 공통
    // =================================================================

    private void dispatchDeliveryComplete(int amount, String platform) {
        sIsDeliveryInProgress = false;
        sLastDeliveryActiveTime = 0L;
        sIsCoupangDeliveryInProgress = false;
        sLastCoupangDeliveryActiveTime = 0L;

        DeliveryEventListener l = sListener;
        if (l != null) {
            try { l.onDeliveryCompleted(platform, amount, null); } catch (Exception e) { Log.e(TAG, "listener failed", e); }
        }
        // 플로팅 알약 HUD 하이라이트 (플랫폼별 구분)
        FloatingPillService.showDeliverySuccess(getApplicationContext(), amount, platform);
    }

    private void notifySynced(String platform, int count, long earnings) {
        DeliveryEventListener l = sListener;
        if (l != null) {
            try { l.onEarningsSynced(platform, count, earnings); } catch (Exception e) { Log.e(TAG, "listener failed", e); }
        }
        FloatingPillService.showDeliverySynced(getApplicationContext(), count, earnings, platform);
    }

    /** 영업일(06:00 ~ 익일 05:59) YYYY-MM-DD */
    static String getBusinessDate() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR_OF_DAY, -6);
        return String.format(Locale.US, "%04d-%02d-%02d",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    /** 영업일 기준 "9월 26일" (자정 이후 배민이 당일 미션을 날짜로 표기하는 것을 "오늘"로 맞출 때 쓴다) */
    static String getBusinessMonthDayLabel() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR_OF_DAY, -6);
        return (cal.get(Calendar.MONTH) + 1) + "월 " + cal.get(Calendar.DAY_OF_MONTH) + "일";
    }

    /** 미션 슬롯("오늘 17:30 ~ 20:30") 시간 안인지. 시간을 못 읽으면 true */
    static boolean isMissionSlotActive(String slot) {
        if (slot == null || slot.isEmpty()) return false;
        Matcher m = SLOT_TIME.matcher(slot);
        if (!m.find()) return true;
        int s = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
        int e = Integer.parseInt(m.group(3)) * 60 + Integer.parseInt(m.group(4));
        Calendar c = Calendar.getInstance();
        int n = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return (e > s) ? (n >= s && n < e) : (n >= s || n < e);
    }

    // ---- 디버그 빌드 전용 화면 덤프 (logcat 만, 파일 저장 없음) ----
    private static final long UI_STATE_DUMP_INTERVAL_MS = 30000L; // 일반 UI 상태 덤프 최소 간격 (30초)
    private static String sLastBaeminEval = "";
    private static long sLastBaeminLogTime = 0L;
    private static String sLastCoupangEval = "";
    private static long sLastCoupangLogTime = 0L;

    private static void debugDump(boolean baemin, String triggerEvent, List<String> texts, String evalResult) {
        if (!DEBUG_DUMP) return;
        long now = System.currentTimeMillis();
        boolean isSpecial = triggerEvent.contains("CLICK") || triggerEvent.contains("COMPLETE")
                         || triggerEvent.contains("MODAL") || triggerEvent.contains("SYNC")
                         || triggerEvent.contains("RECOVERED");
        String lastEval = baemin ? sLastBaeminEval : sLastCoupangEval;
        long lastTime = baemin ? sLastBaeminLogTime : sLastCoupangLogTime;
        boolean evalChanged = (evalResult != null && !evalResult.equals(lastEval));
        // 특수 이벤트이거나, 판정 상태가 바뀌었거나, 30초 이상 지났을 때만 덤프
        if (!(isSpecial || evalChanged || (now - lastTime > UI_STATE_DUMP_INTERVAL_MS))) return;
        if (baemin) {
            sLastBaeminEval = (evalResult != null) ? evalResult : "";
            sLastBaeminLogTime = now;
        } else {
            sLastCoupangEval = (evalResult != null) ? evalResult : "";
            sLastCoupangLogTime = now;
        }

        String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.KOREA).format(new Date(now));
        StringBuilder sb = new StringBuilder();
        sb.append(baemin ? "\n======================================================\n"
                         : "\n================== [COUPANG EATS] ==================\n");
        sb.append("[").append(timeStr).append("] ").append(triggerEvent).append("\n");
        sb.append("Total Texts (").append(texts.size()).append("):\n");
        for (int i = 0; i < texts.size(); i++) {
            sb.append(String.format(Locale.US, "  [%02d] %s\n", i, texts.get(i)));
        }
        if (evalResult != null && !evalResult.isEmpty()) {
            sb.append(">> Eval: ").append(evalResult).append("\n");
        }
        sb.append("======================================================\n");
        Log.i(TAG, sb.toString());
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "DeliveryAccessibilityService interrupted");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "DeliveryAccessibilityService connected and monitoring");
    }
}
