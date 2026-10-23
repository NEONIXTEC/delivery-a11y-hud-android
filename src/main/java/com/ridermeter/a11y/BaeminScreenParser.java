package com.ridermeter.a11y;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [Module Spec]
 * - SSOT: RiderMeter 실측 화면 사전 (1. 배민)
 * - Anchor: Baemin screen parser
 * - Pipeline I/O: Input: Accessibility node texts -> Output: 화면 판정 / 배달료 / 미션 / 마이페이지 실적
 * - Constraints: Pure Java (android.* 금지 — JVM 단위 테스트 `parser-tests.sh`).
 *                상태(isModalOpen·쿨다운·미션 엔진·주문 학습 맵)는 DeliveryAccessibilityService 에 남긴다.
 */
public class BaeminScreenParser {

    // 배달료 정규식: "배달료 3,900원", "중개배달 4,200원", "배달수입 3,500원", "기본 수수료 3,250원", "+3,900원"
    static final Pattern FEE_PATTERN = Pattern.compile("(?:배달료|중개배달(?:료)?|배달\\s*수입|기본배달료|기본\\s*수수료|추가\\s*수수료|멀티보너스\\s*수수료|배달\\s*수수료|수수료|수입)\\s*:?\\s*\\+?\\s*([0-9]{1,3}(?:,[0-9]{3})*)\\s*원");
    static final Pattern PURE_MONEY_PATTERN = Pattern.compile("^\\+?\\s*([0-9]{1,3}(?:,[0-9]{3})*)\\s*원$");

    // 배민 실시간 미션 정규식 ("0, /13, 13건, 11,000원, 오늘 17:30 ~ 20:30" 및 완료 모달)
    static final Pattern BAEMIN_MISSION_PROGRESS_PATTERN = Pattern.compile("(\\d+),\\s*/(\\d+),\\s*(\\d+)건,\\s*([\\d,]+)원,\\s*(오늘\\s*(\\d{2}:\\d{2})\\s*~\\s*(\\d{2}:\\d{2}))");
    static final Pattern BAEMIN_MISSION_DONE_PATTERN = Pattern.compile("^(\\d+)건,\\s*([\\d,]+)원,\\s*(오늘\\s*(\\d{2}:\\d{2})\\s*~\\s*(\\d{2}:\\d{2}))$");
    static final Pattern BAEMIN_MISSION_SCHEDULED_PATTERN = Pattern.compile("^예정,\\s*(\\d+)건,\\s*([\\d,]+)원,\\s*(오늘\\s*(\\d{2}:\\d{2})\\s*~\\s*(\\d{2}:\\d{2}))$");

    // 완료 모달 누락 복구용 (진행 목록 "가게, T주문번호, 배달료 1,880원" / "전달지 주소, T주문번호, 도착예상시간 ...")
    static final Pattern ORDER_STORE_PATTERN = Pattern.compile(", (T[0-9A-Z]{8,}), 배달료 ([0-9]{1,3}(?:,[0-9]{3})*)원");
    static final Pattern ORDER_DROP_PATTERN = Pattern.compile("^(.+), (T[0-9A-Z]{8,}), 도착예상시간");

    private static final Pattern MYPAGE_COUNT_PATTERN = Pattern.compile("^(\\d+)건$");
    private static final Pattern MYPAGE_EARNINGS_PATTERN = Pattern.compile("^([\\d,]+)원$");

    // 배달료 인정 범위 (2026-10-20): 배민 1,350원 콜이 하한 1,500원에 걸려 버려지고 기본값 3,500원으로 찍혔다.
    // "배달료" 같은 글자가 붙은 금액은 메뉴값과 헷갈릴 일이 없으니 하한을 낮춘다. 글자 없는 단독 금액만 1,500원 하한 유지.
    static final int MIN_LABELED_FEE = 500;
    static final int MIN_UNLABELED_FEE = 1500;
    static final int MAX_FEE = 20000;
    // 완료 모달은 확실한데 금액을 못 읽었을 때 넣는 기본값 (쿠팡과 달리 배민은 fail-open)
    static final int DEFAULT_FEE = 3500;

    /** 0. 배차 제안 오퍼 화면 (수락, 거절, 배차 제안 중에는 배달 완료 및 금액 가산 절대 불가) */
    public static boolean isOfferScreen(List<String> texts) {
        for (String text : texts) {
            String ns = text.replace(" ", "");
            if (ns.equals("수락") || ns.equals("거절") || ns.equals("모두거절") ||
                ns.endsWith("모두수락") || ns.equals("배차받기") ||
                ns.contains("주문수락") || ns.contains("배달수락") ||
                ns.contains("신규배달") || ns.contains("배차수락") ||
                ns.contains("신규배차를수락") || ns.contains("배차요청")) {
                return true;
            }
        }
        return false;
    }

    /** 배민 대기 지도 배너("배차를 찾고 있어요" / "대기배달을 ... 배지로 알려드려요!" / "신규배차를 켜고" / "운행시작" / "운행종료됨") */
    public static boolean hasIdleText(List<String> texts) {
        for (String text : texts) {
            String ns = text.replace(" ", "");
            if (ns.contains("배차를찾고있어요") || ns.contains("가까운배차") ||
                ns.contains("대기배달을") || ns.contains("신규배차를켜고") ||
                ns.equals("운행시작") || ns.equals("운행종료됨") || ns.equals("오늘의기록")) {
                return true;
            }
        }
        return false;
    }

    /** 배달 수행 중 카드 (픽업지/전달지 이동, 가게 도착, 전달 완료 ...) */
    public static boolean hasDeliveryCard(List<String> texts) {
        for (String text : texts) {
            String ns = text.replace(" ", "");
            // ⚠️ "픽업 완료 되었습니다"는 배민 앱 루트 트리에 배달 종료 후에도 잔류하는 유령(Ghost) 노드이므로 제외!
            if (ns.contains("픽업지로이동") || ns.contains("배달지로이동") || ns.contains("전달지로이동") ||
                ns.equals("가게도착") || ns.equals("픽업완료") ||
                ns.equals("가게전화") || ns.contains("조리완료예정") || ns.equals("고객요청사항") ||
                ns.equals("전달완료") || ns.contains("TakeDoorSendConfirm")) {
                return true;
            }
        }
        return false;
    }

    /** 배민 고유의 메인/대기/진행 화면 요소 감지 여부 (완료 모달 닫힘 판정용) */
    public static boolean isMainScreen(List<String> texts, boolean hasIdleText, boolean hasDeliveryCard) {
        return hasIdleText || hasDeliveryCard ||
            texts.contains("신규배차") || texts.contains("배차대기중") ||
            texts.contains("지도") || texts.contains("대기") || texts.contains("진행") ||
            texts.contains("가게 도착") || texts.contains("가게도착") || texts.contains("픽업 완료");
    }

    // -------------------------------------------------------------
    // 피크타임 배달미션 (대기 화면 Bottom Sheet / 미션 내역)
    // -------------------------------------------------------------

    public static boolean hasMissionContext(List<String> texts) {
        for (String t : texts) {
            if (t.contains("배달미션") || t.contains("진행중 미션") || t.contains("진행 배달미션") || t.contains("예정 배달미션")) {
                return true;
            }
        }
        return false;
    }

    /** 홈 하단 시트(진행/예정 배달미션)인지 — 아니면 미션 내역 화면 */
    public static boolean isMissionHomeSheet(List<String> texts) {
        for (String t : texts) {
            if (t.contains("진행 배달미션") || t.contains("예정 배달미션") || t.contains("진행중 미션")) {
                return true;
            }
        }
        return false;
    }

    public static class MissionLine {
        public static final int PROGRESS = 1;   // "0, /13, 13건, 11,000원, 오늘 17:30 ~ 20:30"
        public static final int SCHEDULED = 2;  // "예정, 4건, 15,000원, 오늘 17:35 ~ 18:35"
        public static final int NO_PROGRESS = 3; // "13건, 11,000원, 오늘 17:30 ~ 20:30" (홈 시트면 진행 카드, 내역이면 완료)

        public final int kind;
        public final int cur;
        public final int target;
        public final long bonus;
        public final String slot;

        public MissionLine(int kind, int cur, int target, long bonus, String slot) {
            this.kind = kind;
            this.cur = cur;
            this.target = target;
            this.bonus = bonus;
            this.slot = slot;
        }
    }

    /** 미션 한 줄 파싱. 미션 줄이 아니면 null. bizDayLabel = "9월 26일" (자정 이후 표기 정규화용, null 가능) */
    public static MissionLine parseMissionLine(String rawText, String bizDayLabel) {
        // 자정(00:00~05:59) 이후 배민이 당일 영업일 미션을 "9월 26일 11:00 ~ 14:00" 형태로 표기하는 것을 "오늘"로 정규화
        String text = rawText;
        if (bizDayLabel != null && !bizDayLabel.isEmpty() && text.contains(bizDayLabel)) {
            text = text.replace(bizDayLabel, "오늘");
        }

        // 1. 진행 중 미션: "0, /13, 13건, 11,000원, 오늘 17:30 ~ 20:30"
        Matcher mp = BAEMIN_MISSION_PROGRESS_PATTERN.matcher(text);
        if (mp.find()) {
            try {
                int cur = Integer.parseInt(mp.group(1));
                int target = Integer.parseInt(mp.group(2));
                long bonus = Long.parseLong(mp.group(4).replace(",", ""));
                return new MissionLine(MissionLine.PROGRESS, cur, target, bonus, mp.group(5));
            } catch (Exception ignored) {}
        }

        // 1-1. 예정 미션: "예정, 4건, 15,000원, 오늘 17:35 ~ 18:35" → 등록만 (시작되면 배달 건수로 센다)
        Matcher ms = BAEMIN_MISSION_SCHEDULED_PATTERN.matcher(text.trim());
        if (ms.find()) {
            try {
                int target = Integer.parseInt(ms.group(1));
                long bonus = Long.parseLong(ms.group(2).replace(",", ""));
                return new MissionLine(MissionLine.SCHEDULED, 0, target, bonus, ms.group(3));
            } catch (Exception ignored) {}
        }

        // 2. "13건, 11,000원, 오늘 17:30 ~ 20:30" (진행 숫자 없는 줄)
        Matcher md = BAEMIN_MISSION_DONE_PATTERN.matcher(text.trim());
        if (md.find()) {
            try {
                int target = Integer.parseInt(md.group(1));
                long bonus = Long.parseLong(md.group(2).replace(",", ""));
                return new MissionLine(MissionLine.NO_PROGRESS, 0, target, bonus, md.group(3));
            } catch (Exception ignored) {}
        }
        return null;
    }

    // -------------------------------------------------------------
    // 마이페이지(사람모양 아이콘) 오늘 공식 실적
    // -------------------------------------------------------------

    /** 마이페이지 실적 화면 (완료건수 또는 배달내역 버튼이 보일 때) */
    public static boolean isMyPageStats(List<String> texts) {
        return texts.contains("마이페이지") && (texts.contains("완료건수") || texts.contains("배달내역"));
    }

    public static class MyPageResult {
        public final int count;      // 못 읽으면 -1
        public final long earnings;  // 못 읽으면 -1

        public MyPageResult(int count, long earnings) {
            this.count = count;
            this.earnings = earnings;
        }
    }

    public static MyPageResult parseMyPage(List<String> texts) {
        int myPageCount = -1;
        long myPageEarnings = -1;

        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text.equals("완료건수") || text.startsWith("완료건수")) {
                for (int j = i; j <= Math.min(i + 4, texts.size() - 1); j++) {
                    Matcher mc = MYPAGE_COUNT_PATTERN.matcher(texts.get(j).trim());
                    if (mc.find()) {
                        try {
                            myPageCount = Integer.parseInt(mc.group(1));
                            break;
                        } catch (Exception ignored) {}
                    }
                }
            }
            if (text.equals("수입") || text.startsWith("수입")) {
                for (int j = i; j <= Math.min(i + 4, texts.size() - 1); j++) {
                    Matcher me = MYPAGE_EARNINGS_PATTERN.matcher(texts.get(j).trim());
                    if (me.find()) {
                        try {
                            long val = Long.parseLong(me.group(1).replace(",", ""));
                            if (val >= 0 && val < 5000000) {
                                myPageEarnings = val;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        return new MyPageResult(myPageCount, myPageEarnings);
    }

    // -------------------------------------------------------------
    // B. 배달완료 모달 감지 (SSOT: baemin_05_completion_modal_ssot.png)
    // -------------------------------------------------------------

    public static class CompletionModalResult {
        public final boolean isModal;
        public final boolean hasTitle;
        public final boolean hasContinueButton;
        public final boolean hasConfirmButton;
        public final boolean hasCancelButton;
        public final int parsedAmount;   // 못 읽으면 -1
        public final int amount;         // 디스패치 금액 (못 읽으면 DEFAULT_FEE)

        public CompletionModalResult(boolean isModal, boolean hasTitle, boolean hasContinueButton,
                                     boolean hasConfirmButton, boolean hasCancelButton, int parsedAmount) {
            this.isModal = isModal;
            this.hasTitle = hasTitle;
            this.hasContinueButton = hasContinueButton;
            this.hasConfirmButton = hasConfirmButton;
            this.hasCancelButton = hasCancelButton;
            this.parsedAmount = parsedAmount;
            this.amount = (parsedAmount >= MIN_LABELED_FEE && parsedAmount <= MAX_FEE) ? parsedAmount : DEFAULT_FEE;
        }
    }

    public static CompletionModalResult evaluateCompletionModal(List<String> texts) {
        boolean hasCompleteModalTitle = false;
        boolean hasContinueButton = false;      // 묶음배달용: "배달 이어서 하기"
        boolean hasSingleConfirmButton = false; // 단건배달용: "확인", "닫기", "신규배차 켜기", "배달 계속하기"
        boolean hasCancelButton = false;        // 질문/확인 팝업 오탐 방지: "취소", "아니오" (전화 연결/가게 도착 팝업 등 차단)
        int parsedAmount = -1;

        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            String noSpace = text.replace(" ", "");

            // 배달완료 타이틀: "배달완료", "배달 완료!", "배달을 완료했어요", "배달이 완료되었습니다"
            // (진행 중 화면의 '전달 완료' 액션 버튼이나 안내 문구는 완전 제외)
            if (noSpace.equals("배달완료") || noSpace.equals("배달완료!") ||
                noSpace.startsWith("배달을완료") || noSpace.startsWith("배달이완료") ||
                noSpace.equals("배달완료했습니다")) {
                hasCompleteModalTitle = true;
            }

            // 버튼 1: 묶음배달용 ("배달 이어서 하기", "이어서 배달하기", "이어서 진행하기")
            if (noSpace.contains("배달이어서하기") || noSpace.contains("이어서배달하기") || noSpace.contains("이어서진행하기")) {
                hasContinueButton = true;
            }

            // 버튼 2: 단건배달용 ("확인", "닫기", "신규배차 켜기", "배달 계속하기")
            if (noSpace.equals("확인") || noSpace.equals("닫기") || noSpace.contains("신규배차켜기") || noSpace.equals("배달계속하기")) {
                hasSingleConfirmButton = true;
            }

            // 질문/확인 팝업 필터: "취소", "아니오" (전화 연결 팝업, 가게 도착 확인 팝업 등은 완료 모달이 아님)
            if (noSpace.equals("취소") || noSpace.equals("아니오")) {
                hasCancelButton = true;
            }

            // 음식값/메뉴금액/결제금액 노드는 배달료가 아니므로 즉시 스킵
            if (noSpace.contains("메뉴금액") || noSpace.contains("주문금액") || noSpace.contains("결제금액")) {
                continue;
            }
            if (i > 0) {
                String prevNs = texts.get(i - 1).replace(" ", "");
                if (prevNs.contains("메뉴금액") || prevNs.contains("주문금액") || prevNs.contains("결제금액") || prevNs.contains("음식금액")) {
                    continue;
                }
            }

            // 배달료 파싱: "배달료 3,900원", "배달수입 3,900원", "중개배달 4,200원"
            Matcher m = FEE_PATTERN.matcher(text);
            if (m.find()) {
                String numStr = m.group(1);
                if (numStr != null) {
                    try {
                        int amount = Integer.parseInt(numStr.replace(",", ""));
                        if (amount >= MIN_LABELED_FEE && amount <= MAX_FEE) {
                            parsedAmount = amount;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }

            // 노드가 분리된 경우: "배달료" -> 다음 노드가 "3,900원" / "+3,900원"
            if ((text.equals("배달료") || text.equals("중개배달료") || text.equals("중개배달") ||
                 text.equals("배달수입") || noSpace.equals("기본배달료") || noSpace.equals("배달비")) && i + 1 < texts.size()) {
                Matcher m2 = PURE_MONEY_PATTERN.matcher(texts.get(i + 1));
                if (m2.find()) {
                    String numStr = m2.group(1);
                    if (numStr != null) {
                        try {
                            int amount = Integer.parseInt(numStr.replace(",", ""));
                            if (amount >= MIN_LABELED_FEE && amount <= MAX_FEE) {
                                parsedAmount = amount;
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            // 단독 금액 노드 보조 파싱: "3,900원" / "+3,900원" (배달완료 타이틀이 있을 때만 유효)
            if (parsedAmount <= 0 && hasCompleteModalTitle) {
                Matcher m3 = PURE_MONEY_PATTERN.matcher(text);
                if (m3.find()) {
                    try {
                        int amount = Integer.parseInt(m3.group(1).replace(",", ""));
                        if (amount >= MIN_UNLABELED_FEE && amount <= MAX_FEE) {
                            parsedAmount = amount;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        // 팝업 조건 검증 (SSOT):
        // 1) 묶음배달: "배달 이어서 하기" 버튼 일치 시 확정
        // 2) 단건배달: "배달완료" 타이틀 + (확인/닫기/신규배차켜기) 버튼 + 취소 버튼 없음
        // (주의: 가게도착/고객전화 등 단순 확인 팝업은 타이틀 부재 및 취소 버튼으로 100% 필터링)
        boolean isCompletionModal = (hasContinueButton) ||
            (hasCompleteModalTitle && hasSingleConfirmButton && !hasCancelButton);

        return new CompletionModalResult(isCompletionModal, hasCompleteModalTitle, hasContinueButton,
                hasSingleConfirmButton, hasCancelButton, parsedAmount);
    }
}
