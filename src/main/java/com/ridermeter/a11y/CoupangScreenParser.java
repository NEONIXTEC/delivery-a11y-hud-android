package com.ridermeter.a11y;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [Module Spec]
 * - SSOT: RiderMeter 실측 화면 사전 (2. 쿠팡이츠)
 * - Anchor: Coupang screen parser
 * - Pipeline I/O: Input: Accessibility node texts -> Output: Structured results
 * - Constraints: Pure Java (no Android Context dependency for unit testing).
 *                Strict node-boundary parsing. Never invent fake amounts (fail-closed).
 */
public class CoupangScreenParser {

    private static final Pattern MONEY_PATTERN = Pattern.compile("([0-9]{1,3}(?:,[0-9]{3})*)\\s*원");
    private static final Pattern DELIVERY_COUNT_PATTERN = Pattern.compile("배달\\s*(\\d+)\\s*건");
    private static final Pattern TIME_COMPLETE_PATTERN = Pattern.compile("(\\d{1,2}:\\d{2})\\s*완료");

    public static class EarningsSyncResult {
        public final boolean valid;
        public final int count;
        public final long fee;
        public final long missionBonus;
        public final long total;

        public EarningsSyncResult(boolean valid, int count, long fee, long missionBonus) {
            this.valid = valid;
            this.count = count;
            this.fee = fee;
            this.missionBonus = missionBonus;
            this.total = (valid && fee >= 0) ? (fee + missionBonus) : 0L;
        }

        public static EarningsSyncResult invalid() {
            return new EarningsSyncResult(false, -1, -1L, 0L);
        }
    }

    public static class CompletionModalResult {
        public final boolean isModal;
        public final int amount;

        public CompletionModalResult(boolean isModal, int amount) {
            this.isModal = isModal;
            this.amount = amount;
        }

        public static CompletionModalResult notModal() {
            return new CompletionModalResult(false, 0);
        }
    }

    public static class HistoryOrderItem {
        public final String time;
        public final String storeName;
        public final int amount;

        public HistoryOrderItem(String time, String storeName, int amount) {
            this.time = time;
            this.storeName = storeName;
            this.amount = amount;
        }
    }

    /** 쿠팡 배차 제안 오퍼 화면 (최우선 차단 — 콜 금액이 완료로 잡히면 안 된다) */
    public static boolean isOfferScreen(List<String> texts) {
        for (String text : texts) {
            String ns = text.replace(" ", "");
            if (ns.contains("주문수락") || ns.contains("배차수락") ||
                ns.contains("배차요청") || ns.contains("신규배달") ||
                ns.contains("배달수락") || ns.contains("새로운배달") ||
                ns.equals("수락") || ns.equals("거절") || ns.equals("모두거절") ||
                ns.equals("✕") || ns.equals("X") || ns.equals("배차받기")) {
                return true;
            }
        }
        return false;
    }

    /** 픽업 및 배달 수행 중 화면 (음식값 13,500원 등 오탐 차단, 내 수입 조기 동기화 차단용) */
    public static boolean isInFlightScreen(List<String> texts) {
        for (String text : texts) {
            String ns = text.replace(" ", "");
            if (ns.equals("매장도착") || ns.equals("매장도착하기") ||
                ns.equals("픽업완료") || ns.equals("픽업완료하기") ||
                ns.equals("배달상태촬영") || ns.equals("사진다시촬영") ||
                ns.contains("배달완료사진은고객님과") ||
                ns.contains("배달완료후인증사진") ||
                ns.contains("조리완료예상")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 쿠팡 '내 수입' 화면 공식 실적 동기화 파서 (B1 친구초대 오파싱 & B2 미션 0원 원천 방어)
     * - 날짜 행과 같은 노드 내부의 배달 건수/수수료만 파싱 (인접 노드 무차별 스캔 배제)
     * - 친구 초대, 프로모션 배너 등 날짜 없는 외부 노드는 100% 무시
     */
    public static EarningsSyncResult parseEarningsSync(List<String> texts, String bizDateMMDD) {
        if (texts == null || texts.isEmpty() || bizDateMMDD == null || bizDateMMDD.isEmpty()) {
            return EarningsSyncResult.invalid();
        }

        boolean hasEarningsHeader = false;
        for (String text : texts) {
            if (text == null) continue;
            String ns = text.replace(" ", "");
            if (ns.contains("내수입") || ns.contains("일별배달수수료")) {
                hasEarningsHeader = true;
                break;
            }
        }
        if (!hasEarningsHeader) {
            return EarningsSyncResult.invalid();
        }

        int todayCount = -1;
        long todayFee = -1L;
        long todayMissionBonus = 0L;

        for (String text : texts) {
            if (text == null) continue;
            String ns = text.replace(" ", "");

            // 1. 당일 배달 수수료 노드 (예: "10/11 일  배달 5건\n15,159원")
            // 같은 노드 안에 날짜(10/11)와 "배달 N건"이 반드시 함께 존재해야 함
            if (ns.contains(bizDateMMDD) && text.contains("배달") && text.contains("건")) {
                Matcher mc = DELIVERY_COUNT_PATTERN.matcher(text);
                if (mc.find()) {
                    try {
                        todayCount = Integer.parseInt(mc.group(1));
                    } catch (NumberFormatException ignored) {}
                }
                Matcher mf = MONEY_PATTERN.matcher(text);
                if (mf.find()) {
                    try {
                        todayFee = Long.parseLong(mf.group(1).replace(",", ""));
                    } catch (NumberFormatException ignored) {}
                }
            }

            // 2. 미션 보너스 노드 (예: "미션 보너스\n10/11 일 [지역 한정] 3건에 7,500원 깜짝 보너스!\n7,500원")
            // 같은 노드 안에 미션 키워드와 당일 날짜(10/11)가 함께 들어있는 경우만 인정
            if ((text.contains("미션") || text.contains("보너스")) && ns.contains(bizDateMMDD)) {
                String[] lines = text.split("\n");
                for (String line : lines) {
                    // 미션 타이틀 라인 ("[지역 한정] 3건에 7,500원 깜짝 보너스!")에 언급된 금액과 구별하기 위해
                    // 단독 금액 라인 또는 순수 금액 라인 우선 추출
                    String trimLine = line.trim();
                    if (trimLine.matches("^[0-9]{1,3}(?:,[0-9]{3})*\\s*원$")) {
                        Matcher mm = MONEY_PATTERN.matcher(trimLine);
                        if (mm.find()) {
                            try {
                                long val = Long.parseLong(mm.group(1).replace(",", ""));
                                if (val > 0 && val < 1000000) {
                                    todayMissionBonus += val;
                                    break;
                                }
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
        }

        if (todayCount >= 0 && todayFee >= 0) {
            return new EarningsSyncResult(true, todayCount, todayFee, todayMissionBonus);
        }

        return EarningsSyncResult.invalid();
    }

    /**
     * 쿠팡 배달 완료 팝업/모달 감지 파서 (R1 위험 완화 & B5 가짜 4000원 제거)
     */
    public static CompletionModalResult parseCompletionModal(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return CompletionModalResult.notModal();
        }

        boolean hasConfirmButton = false;
        boolean hasCompleteTitle = false;
        int parsedAmount = 0;

        // 화면 전체 수준의 안전 가드: 촬영 화면이나 안내 화면은 배달 완료 모달이 아님
        for (String text : texts) {
            if (text == null) continue;
            String ns = text.replace(" ", "");
            if (ns.contains("사진") || ns.contains("촬영") || ns.contains("인증사진") || ns.contains("주의")) {
                return CompletionModalResult.notModal();
            }
        }

        for (String text : texts) {
            if (text == null) continue;
            String ns = text.replace(" ", "");

            // 1. 확인/닫기 버튼 (촬영 중 "사진 다시 촬영" 등과 엄격 분리)
            if (ns.equals("확인") || ns.equals("닫기") || ns.contains("다음배달") || ns.contains("배달계속하기")) {
                hasConfirmButton = true;
            }

            // 2. 배달 완료 타이틀

            String[] lines = text.split("\n");
            String firstLine = (lines.length > 0) ? lines[0].replace(" ", "") : "";
            if (firstLine.endsWith("배달완료") || firstLine.equals("배달을완료했습니다") || firstLine.equals("전달완료")) {
                hasCompleteTitle = true;

                // 타이틀 노드 자체에 금액이 포함된 경우 ("가나불닭 배달 완료\n4,000원")
                Matcher mSelf = MONEY_PATTERN.matcher(text);
                if (mSelf.find()) {
                    try {
                        int val = Integer.parseInt(mSelf.group(1).replace(",", ""));
                        if (val >= 1500 && val <= 35000) {
                            parsedAmount = val;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }

            // 3. 기본료 노드 보조 파싱 ("기본료\n4,000원")
            if (parsedAmount <= 0 && (text.contains("기본료") || text.contains("배달료"))) {
                Matcher mFee = MONEY_PATTERN.matcher(text);
                if (mFee.find()) {
                    try {
                        int val = Integer.parseInt(mFee.group(1).replace(",", ""));
                        if (val >= 1500 && val <= 35000) {
                            parsedAmount = val;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        if (hasCompleteTitle && hasConfirmButton) {
            // fail-closed: 금액을 못 찾으면 0원 (가짜 4,000원 절대 생성 금지)
            return new CompletionModalResult(true, parsedAmount);
        }

        return CompletionModalResult.notModal();
    }

    /** 배달완료 모달 안의 깜짝미션 줄. success=true 일 때만 bonus 를 더한다. */
    public static class ModalMissionResult {
        public final boolean success;
        public final long bonus;
        public final int target;       // "3건 배달" 의 3 (모르면 0)
        public final int remaining;    // "2건 더 하면 1만 5천원" 의 2 (진행 중일 때만, 모르면 0)

        public ModalMissionResult(boolean success, long bonus, int target, int remaining) {
            this.success = success;
            this.bonus = bonus;
            this.target = target;
            this.remaining = remaining;
        }

        public static ModalMissionResult none() {
            return new ModalMissionResult(false, 0L, 0, 0);
        }
    }

    private static final Pattern MISSION_SUCCESS_PATTERN = Pattern.compile("성공!?\\s*([0-9만천백,\\s]+?)\\s*원\\s*보너스");
    private static final Pattern MISSION_REMAIN_PATTERN = Pattern.compile("(\\d+)\\s*건\\s*더\\s*하면\\s*([0-9만천백,\\s]+?)\\s*원");
    private static final Pattern MISSION_TARGET_PATTERN = Pattern.compile("(\\d+)\\s*건\\s*배달");

    /**
     * "1만 5천원" / "1만5천원" / "5천원" / "15,000원" → 원 단위. 읽을 수 없으면 0.
     * (2026-10-18 실측: 쿠팡 깜미 모달은 금액을 한글 단위로 적는다 — MONEY_PATTERN 으로는 못 읽는다)
     */
    public static long parseKoreanWon(String s) {
        if (s == null) return 0L;
        String t = s.replace(" ", "").replace(",", "").replace("원", "");
        Matcher m = Pattern.compile("(\\d+)(만|천|백)?").matcher(t);
        long total = 0L;
        boolean any = false;
        while (m.find()) {
            long n;
            try {
                n = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return 0L;
            }
            String u = m.group(2);
            long mult = "만".equals(u) ? 10000L : "천".equals(u) ? 1000L : "백".equals(u) ? 100L : 1L;
            total += n * mult;
            any = true;
        }
        return any ? total : 0L;
    }

    /**
     * 쿠팡 배달완료 모달의 미션 줄 파서.
     *   성공:  "진행한 미션 1개\n성공! 1만 5천원 보너스\n3건 배달"
     *   진행:  "진행한 미션 1개\n2건 더 하면 1만 5천원\n3건 배달"
     * 완료 모달(parseCompletionModal)이 아닌 화면에서는 호출하지 않는다.
     */
    public static ModalMissionResult parseModalMission(List<String> texts) {
        if (texts == null) return ModalMissionResult.none();
        for (String text : texts) {
            if (text == null || !text.contains("미션")) continue;
            Matcher ms = MISSION_SUCCESS_PATTERN.matcher(text);
            Matcher mt = MISSION_TARGET_PATTERN.matcher(text);
            int target = mt.find() ? safeInt(mt.group(1)) : 0;
            if (ms.find()) {
                long bonus = parseKoreanWon(ms.group(1));
                if (bonus >= 1000L && bonus <= 500000L) {
                    return new ModalMissionResult(true, bonus, target, 0);
                }
            }
            Matcher mr = MISSION_REMAIN_PATTERN.matcher(text);
            if (mr.find()) {
                long bonus = parseKoreanWon(mr.group(2));
                return new ModalMissionResult(false, bonus, target, safeInt(mr.group(1)));
            }
        }
        return ModalMissionResult.none();
    }

    private static int safeInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 쿠팡 배달 내역 화면 (History) 파서 - 주문 단위 실적 추출
     * 예: "2,874원\n2P\n18:19 완료\n | \n 0.1km\nABC 샘플점\n가상구 예시로15가길\n다른 주문과 함께 배달한 건"
     */
    public static List<HistoryOrderItem> parseDeliveryHistory(List<String> texts) {
        List<HistoryOrderItem> items = new ArrayList<>();
        if (texts == null || texts.isEmpty()) return items;

        for (String text : texts) {
            if (text == null) continue;
            if (!text.contains("완료") || !text.contains("원")) continue;

            Matcher mt = TIME_COMPLETE_PATTERN.matcher(text);
            Matcher mm = MONEY_PATTERN.matcher(text);

            if (mt.find() && mm.find()) {
                String time = mt.group(1);
                int amount = 0;
                try {
                    amount = Integer.parseInt(mm.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {}

                // 매장명 추출: 줄바꿈 분리 후 km 줄 다음 줄
                String storeName = "";
                String[] lines = text.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    if (lines[i].contains("km") && (i + 1 < lines.length)) {
                        storeName = lines[i + 1].trim();
                        break;
                    }
                }

                if (amount > 0 && !time.isEmpty()) {
                    items.add(new HistoryOrderItem(time, storeName, amount));
                }
            }
        }

        return items;
    }
}
