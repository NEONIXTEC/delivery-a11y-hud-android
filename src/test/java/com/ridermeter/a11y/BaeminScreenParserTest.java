package com.ridermeter.a11y;

import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.ridermeter.a11y.ScreenFixtures.*;
import static org.junit.Assert.*;

/**
 * 배민 화면 판정 회귀 테스트 (BaeminScreenParser = DeliveryAccessibilityService.handleBaemin 의 순수 판정부).
 * 픽스처: ScreenFixtures (실측 스크린샷 SSOT + 실측 오탐 사례).
 * KNOWN ISSUE 테스트는 @Ignore — 바라는 동작을 적어 두었고, 지금 코드는 통과하지 못한다. 고치면 @Ignore 를 뗀다.
 */
public class BaeminScreenParserTest {

    // -------------------------------------------------------------
    // 0. 배차 제안 오퍼 필터 (handleBaemin 최상단 return)
    // -------------------------------------------------------------

    @Test
    public void offer_baemin01_batchOffer_isOffer() {
        assertTrue(BaeminScreenParser.isOfferScreen(BAEMIN_01_OFFER_BATCH));
    }

    @Test
    public void offer_baemin02_bannerOffer_isOffer() {
        assertTrue(BaeminScreenParser.isOfferScreen(BAEMIN_02_OFFER_BANNER));
    }

    @Test
    public void offer_v1_18_26_newDispatchToggle_blockedBeforeModalCheck() {
        // 모달 판정만 보면 완료처럼 보이지만(신규배차 켜기 = 단건 확인 버튼) 오퍼 필터가 먼저 막는다 → 순서가 핵심
        assertTrue(BaeminScreenParser.isOfferScreen(BAEMIN_V1_18_26_OFFER_WITH_NEW_DISPATCH_TOGGLE));
        assertTrue(BaeminScreenParser.evaluateCompletionModal(BAEMIN_V1_18_26_OFFER_WITH_NEW_DISPATCH_TOGGLE).isModal);
    }

    @Test
    public void offer_baemin05_completionModalBackground_notOffer() {
        // SSOT 3.3: 배경의 '신규배차' 토글·'가게 도착'·'배달 요청사항' 으로 오탈출 금지
        assertFalse(BaeminScreenParser.isOfferScreen(BAEMIN_05_COMPLETION_MODAL_BUNDLE));
    }

    @Test
    public void offer_nonOfferScreens_notOffer() {
        assertFalse(BaeminScreenParser.isOfferScreen(BAEMIN_03_IDLE_MAP));
        assertFalse(BaeminScreenParser.isOfferScreen(BAEMIN_04_PRE_COMPLETION_PHOTO));
        assertFalse(BaeminScreenParser.isOfferScreen(BAEMIN_V1_18_26_NEXT_CALL_IN_FLIGHT));
        assertFalse(BaeminScreenParser.isOfferScreen(BAEMIN_V1_18_26_PICKUP_WAIT_POPUP_MENU_PRICE));
        assertFalse(BaeminScreenParser.isOfferScreen(BAEMIN_06_MYPAGE_TODAY));
    }

    @Test
    public void offer_customerCallLegalText_tripsOfferFilter_currentBehavior() {
        // 현재 동작 고정: 고지문 "배차 수락시점부터" → 공백 제거 "배차수락시점부터" 가 contains("배차수락") 에 걸린다.
        // 이 팝업에서는 무해(어차피 완료 아님)하지만, 같은 고지문이 완료 모달 배경에 깔리면 완료가 통째로 버려진다.
        assertTrue(BaeminScreenParser.isOfferScreen(BAEMIN_V1_18_26_CUSTOMER_CALL_POPUP));
    }

    // -------------------------------------------------------------
    // 대기 / 배달 중 카드
    // -------------------------------------------------------------

    @Test
    public void state_baemin03_idleMap() {
        assertTrue(BaeminScreenParser.hasIdleText(BAEMIN_03_IDLE_MAP));
        assertFalse(BaeminScreenParser.hasDeliveryCard(BAEMIN_03_IDLE_MAP));
        assertTrue(BaeminScreenParser.isMainScreen(BAEMIN_03_IDLE_MAP, true, false));
    }

    @Test
    public void state_inFlightCards() {
        assertTrue(BaeminScreenParser.hasDeliveryCard(BAEMIN_V1_18_26_NEXT_CALL_IN_FLIGHT));
        assertTrue(BaeminScreenParser.hasDeliveryCard(BAEMIN_04_PRE_COMPLETION_PHOTO));   // '전달 완료' 버튼
        assertTrue(BaeminScreenParser.hasDeliveryCard(BAEMIN_05_COMPLETION_MODAL_BUNDLE)); // 묶음 다음 주문 배경
        assertFalse(BaeminScreenParser.hasIdleText(BAEMIN_V1_18_26_NEXT_CALL_IN_FLIGHT));
    }

    @Test
    public void state_ghostPickupDoneNode_notDeliveryCard() {
        // "픽업 완료 되었습니다" 는 배달 종료 후에도 남는 유령 노드 — equals("픽업완료") 만 인정
        assertFalse(BaeminScreenParser.hasDeliveryCard(texts("픽업 완료 되었습니다")));
        assertTrue(BaeminScreenParser.hasDeliveryCard(texts("픽업 완료")));
    }

    @Test
    public void state_mainScreen_onlyFromBaeminElements() {
        assertFalse(BaeminScreenParser.isMainScreen(texts("홈", "전화", "메시지"), false, false));
        assertTrue(BaeminScreenParser.isMainScreen(texts("신규배차"), false, false));
    }

    // -------------------------------------------------------------
    // B. 배달완료 모달 (SSOT baemin_05)
    // -------------------------------------------------------------

    @Test
    public void modal_baemin05_bundle_ssot_3900() {
        BaeminScreenParser.CompletionModalResult r = BaeminScreenParser.evaluateCompletionModal(BAEMIN_05_COMPLETION_MODAL_BUNDLE);
        assertTrue(r.isModal);
        assertTrue(r.hasTitle);
        assertTrue(r.hasContinueButton);
        assertEquals(3900, r.parsedAmount);
        assertEquals(3900, r.amount);
    }

    @Test
    public void modal_single_confirm_4740() {
        BaeminScreenParser.CompletionModalResult r = BaeminScreenParser.evaluateCompletionModal(BAEMIN_COMPLETION_MODAL_SINGLE_CONFIRM);
        assertTrue(r.isModal);
        assertFalse(r.hasContinueButton);
        assertTrue(r.hasConfirmButton);
        assertEquals(4740, r.amount);
    }

    @Test
    public void modal_single_newDispatchOnButton_2500() {
        BaeminScreenParser.CompletionModalResult r = BaeminScreenParser.evaluateCompletionModal(BAEMIN_COMPLETION_MODAL_SINGLE_NEW_DISPATCH_ON);
        assertTrue(r.isModal);
        assertEquals(2500, r.amount);
    }

    @Test
    public void modal_v1_18_53_splitLabel_1350_notDefault3500() {
        BaeminScreenParser.CompletionModalResult r = BaeminScreenParser.evaluateCompletionModal(BAEMIN_V1_18_53_COMPLETION_SPLIT_LABEL_1350);
        assertTrue(r.isModal);
        assertEquals(1350, r.amount);
    }

    @Test
    public void modal_labeledFeeVariants() {
        assertEquals(3500, modalAmount("배달완료", "배달수입 3,500원", "확인"));
        assertEquals(4200, modalAmount("배달완료", "중개배달 4,200원", "확인"));
        assertEquals(3900, modalAmount("배달완료", "배달료: 3,900원", "확인"));
        assertEquals(3900, modalAmount("배달완료", "배달료 +3,900원", "확인"));
        assertEquals(600, modalAmount("배달완료", "배달료 600원", "확인"));   // 라벨 붙은 금액 하한 500원
    }

    @Test
    public void modal_unlabeledPlusAmount_3900() {
        BaeminScreenParser.CompletionModalResult r = BaeminScreenParser.evaluateCompletionModal(BAEMIN_COMPLETION_UNLABELED_PLUS_AMOUNT);
        assertTrue(r.isModal);
        assertEquals(3900, r.amount);
    }

    @Test
    public void modal_unlabeledBelow1500_fallsBackTo3500() {
        // 현재 동작 고정: 라벨 없는 단독 금액 하한 1,500원 (메뉴값 혼동 방지) → 기본값 3,500원
        BaeminScreenParser.CompletionModalResult r = BaeminScreenParser.evaluateCompletionModal(texts("배달완료", "+1,350원", "확인"));
        assertTrue(r.isModal);
        assertEquals(-1, r.parsedAmount);
        assertEquals(BaeminScreenParser.DEFAULT_FEE, r.amount);
    }

    @Test
    public void modal_noAmount_defaults3500() {
        BaeminScreenParser.CompletionModalResult r = BaeminScreenParser.evaluateCompletionModal(BAEMIN_COMPLETION_NO_AMOUNT);
        assertTrue(r.isModal);
        assertEquals(-1, r.parsedAmount);
        assertEquals(3500, r.amount);
    }

    @Test
    public void modal_overMaxFee_defaults3500() {
        assertEquals(3500, modalAmount("배달완료", "배달료 25,000원", "확인"));
    }

    @Test
    public void modal_titleVariants() {
        assertTrue(BaeminScreenParser.evaluateCompletionModal(texts("배달 완료!", "배달료 3,000원", "확인")).isModal);
        assertTrue(BaeminScreenParser.evaluateCompletionModal(texts("배달을 완료했어요", "배달료 3,000원", "닫기")).isModal);
        assertTrue(BaeminScreenParser.evaluateCompletionModal(texts("배달이 완료되었습니다", "배달료 3,000원", "배달 계속하기")).isModal);
        // 진행 중 '전달 완료' 버튼은 타이틀이 아니다
        assertFalse(BaeminScreenParser.evaluateCompletionModal(texts("전달 완료", "확인")).isModal);
    }

    @Test
    public void modal_v1_18_26_pickupWaitPopup_menuPrice_notCounted() {
        BaeminScreenParser.CompletionModalResult r = BaeminScreenParser.evaluateCompletionModal(BAEMIN_V1_18_26_PICKUP_WAIT_POPUP_MENU_PRICE);
        assertFalse(r.isModal);
        assertEquals(-1, r.parsedAmount);   // 메뉴금액 다음 15,000원 스킵
    }

    @Test
    public void modal_v1_18_26_menuPriceGuard_insideRealModal() {
        // 완료 타이틀 뒤에 '메뉴금액' + 15,000원 이 와도 배달료로 쓰지 않는다 → 기본값
        BaeminScreenParser.CompletionModalResult r = BaeminScreenParser.evaluateCompletionModal(
                texts("배달완료", "메뉴금액", "15,000원", "주문금액 15,000원", "결제금액", "16,000원", "확인"));
        assertTrue(r.isModal);
        assertEquals(-1, r.parsedAmount);
        assertEquals(3500, r.amount);
    }

    @Test
    public void modal_v1_18_26_customerCallPopup_legalText_notTitle() {
        BaeminScreenParser.CompletionModalResult r = BaeminScreenParser.evaluateCompletionModal(BAEMIN_V1_18_26_CUSTOMER_CALL_POPUP);
        assertFalse(r.hasTitle);
        assertTrue(r.hasConfirmButton);   // '닫기'
        assertFalse(r.isModal);
    }

    @Test
    public void modal_v1_18_26_storeArrivalConfirmPopup_notModal() {
        BaeminScreenParser.CompletionModalResult r = BaeminScreenParser.evaluateCompletionModal(BAEMIN_V1_18_26_STORE_ARRIVAL_CONFIRM_POPUP);
        assertFalse(r.isModal);
        assertTrue(r.hasCancelButton);
    }

    @Test
    public void modal_titleWithCancelButton_notModal() {
        assertFalse(BaeminScreenParser.evaluateCompletionModal(BAEMIN_TITLE_WITH_CANCEL_QUESTION).isModal);
    }

    @Test
    public void modal_v1_18_26_transition_nextCallInFlight_notModal() {
        // 화면 전환 추측(TRANSITION) 삭제 후: 다음 콜 카드로 넘어가도 완료 아님
        assertFalse(BaeminScreenParser.evaluateCompletionModal(BAEMIN_V1_18_26_NEXT_CALL_IN_FLIGHT).isModal);
        assertFalse(BaeminScreenParser.evaluateCompletionModal(BAEMIN_04_PRE_COMPLETION_PHOTO).isModal);
        assertFalse(BaeminScreenParser.evaluateCompletionModal(BAEMIN_03_IDLE_MAP).isModal);
        assertFalse(BaeminScreenParser.evaluateCompletionModal(BAEMIN_06_MYPAGE_TODAY).isModal);
    }

    @Ignore("KNOWN ISSUE: 배달료는 마지막 매치가 이긴다 — 모달 노드 뒤에 배경 카드의 '배달료 3,340원'이 오면 3,340원으로 덮인다")
    @Test
    public void knownIssue_modal_backgroundCardFeeAfterModal_overwritesModalFee() {
        // 수집 순서는 activeRoot 먼저 → getWindows() 의 나머지 배민 창 나중. 모달이 별도 활성 창이면(덤프로 미확인)
        // 배경 주문 카드의 금액이 마지막에 와서 모달 금액을 덮는다.
        // 현재: amount=3340, 바라는 값: 모달의 3900
        List<String> t = new ArrayList<>();
        t.add("배달완료");
        t.add("배달료 3,900원");
        t.add("배달 이어서 하기");
        t.add("픽업지로 이동해주세요");
        t.add("가나다떡볶이 가상점");
        t.add("배달료 3,340원");
        assertEquals(3900, BaeminScreenParser.evaluateCompletionModal(t).amount);
    }

    @Ignore("KNOWN ISSUE: 모달 금액 노드가 아직 없을 때, 타이틀 뒤 배경 메뉴 가격(라벨 없는 19,000원)을 배달료로 쓴다")
    @Test
    public void knownIssue_modal_titleFirst_unlabeledMenuPriceTakenAsFee() {
        // 메뉴금액 스킵 가드는 바로 앞 노드가 '메뉴금액/주문금액/결제금액/음식금액' 일 때만 동작한다.
        // 현재: amount=19000, 바라는 값: 기본값 3500 (메뉴 가격은 배달료가 아니다)
        List<String> t = texts("배달완료", "배달 이어서 하기", "주문내역", "메뉴", "가격", "양념구이 1개", "19,000원");
        assertEquals(3500, BaeminScreenParser.evaluateCompletionModal(t).amount);
    }

    // -------------------------------------------------------------
    // 피크타임 배달미션
    // -------------------------------------------------------------

    @Test
    public void mission_v1_18_28_progressLine() {
        BaeminScreenParser.MissionLine m = BaeminScreenParser.parseMissionLine(MISSION_V1_18_28_PROGRESS, "10월 23일");
        assertNotNull(m);
        assertEquals(BaeminScreenParser.MissionLine.PROGRESS, m.kind);
        assertEquals(0, m.cur);
        assertEquals(13, m.target);
        assertEquals(11000L, m.bonus);
        assertEquals("오늘 17:30 ~ 20:30", m.slot);
    }

    @Test
    public void mission_v1_18_28_noProgressLine() {
        BaeminScreenParser.MissionLine m = BaeminScreenParser.parseMissionLine(MISSION_V1_18_28_NO_PROGRESS, null);
        assertNotNull(m);
        assertEquals(BaeminScreenParser.MissionLine.NO_PROGRESS, m.kind);
        assertEquals(13, m.target);
        assertEquals(11000L, m.bonus);
        assertEquals("오늘 17:30 ~ 20:30", m.slot);
    }

    @Test
    public void mission_v1_18_50_scheduledLine() {
        BaeminScreenParser.MissionLine m = BaeminScreenParser.parseMissionLine(MISSION_V1_18_50_SCHEDULED, "");
        assertNotNull(m);
        assertEquals(BaeminScreenParser.MissionLine.SCHEDULED, m.kind);
        assertEquals(4, m.target);
        assertEquals(15000L, m.bonus);
        assertEquals("오늘 17:35 ~ 18:35", m.slot);
    }

    @Test
    public void mission_afterMidnight_bizDayLabelNormalizedToToday() {
        String line = "2, /13, 13건, 11,000원, 9월 26일 11:00 ~ 14:00";
        BaeminScreenParser.MissionLine m = BaeminScreenParser.parseMissionLine(line, "9월 26일");
        assertNotNull(m);
        assertEquals(2, m.cur);
        assertEquals("오늘 11:00 ~ 14:00", m.slot);
        // 라벨이 다른 날이면 정규화 안 됨 → 미션 줄 아님
        assertNull(BaeminScreenParser.parseMissionLine(line, "9월 27일"));
    }

    @Test
    public void mission_surroundingWhitespace_trimmedForAnchoredPatterns() {
        assertNotNull(BaeminScreenParser.parseMissionLine("  13건, 11,000원, 오늘 17:30 ~ 20:30 ", null));
        assertNotNull(BaeminScreenParser.parseMissionLine(" 예정, 4건, 15,000원, 오늘 17:35 ~ 18:35", null));
    }

    @Test
    public void mission_nonMissionLines_null() {
        assertNull(BaeminScreenParser.parseMissionLine("배달료 3,900원", null));
        assertNull(BaeminScreenParser.parseMissionLine("21건", null));
        assertNull(BaeminScreenParser.parseMissionLine("13건, 11,000원", null));
        assertNull(BaeminScreenParser.parseMissionLine("배달미션", null));
    }

    @Test
    public void mission_v1_18_50_homeSheetMultiMission_eachCardParsed() {
        assertTrue(BaeminScreenParser.hasMissionContext(BAEMIN_V1_18_50_HOME_SHEET_MULTI_MISSION));
        assertTrue(BaeminScreenParser.isMissionHomeSheet(BAEMIN_V1_18_50_HOME_SHEET_MULTI_MISSION));
        List<BaeminScreenParser.MissionLine> lines = parseAll(BAEMIN_V1_18_50_HOME_SHEET_MULTI_MISSION);
        assertEquals(3, lines.size());
        assertEquals(BaeminScreenParser.MissionLine.PROGRESS, lines.get(0).kind);
        assertEquals(3, lines.get(0).cur);
        assertEquals(20000L, lines.get(0).bonus);
        assertEquals(BaeminScreenParser.MissionLine.PROGRESS, lines.get(1).kind);
        assertEquals(1, lines.get(1).cur);
        assertEquals(4, lines.get(1).target);
        assertEquals(BaeminScreenParser.MissionLine.SCHEDULED, lines.get(2).kind);
        assertEquals("오늘 18:50 ~ 19:50", lines.get(2).slot);
    }

    @Test
    public void mission_historyScreen_notHomeSheet() {
        // 미션 내역 화면: 숫자 없는 줄 = 완료로 반영 (서비스가 onHomeSheet=false 로 처리)
        assertTrue(BaeminScreenParser.hasMissionContext(BAEMIN_MISSION_HISTORY));
        assertFalse(BaeminScreenParser.isMissionHomeSheet(BAEMIN_MISSION_HISTORY));
        List<BaeminScreenParser.MissionLine> lines = parseAll(BAEMIN_MISSION_HISTORY);
        assertEquals(2, lines.size());
        assertEquals(BaeminScreenParser.MissionLine.NO_PROGRESS, lines.get(0).kind);
        assertEquals(BaeminScreenParser.MissionLine.PROGRESS, lines.get(1).kind);   // 지난 슬롯 → 서비스가 isMissionSlotActive 로 거른다
    }

    @Test
    public void mission_contextFromMyPageMenu_currentBehavior() {
        // 마이페이지 하단 메뉴 '배달미션' 글자만으로도 미션 파싱 경로에 들어간다 (줄이 없으니 무해)
        assertTrue(BaeminScreenParser.hasMissionContext(BAEMIN_06_MYPAGE_TODAY));
        assertTrue(parseAll(BAEMIN_06_MYPAGE_TODAY).isEmpty());
        assertFalse(BaeminScreenParser.hasMissionContext(BAEMIN_05_COMPLETION_MODAL_BUNDLE));
    }

    // -------------------------------------------------------------
    // 마이페이지 오늘 실적
    // -------------------------------------------------------------

    @Test
    public void myPage_baemin06_today_21_75450() {
        assertTrue(BaeminScreenParser.isMyPageStats(BAEMIN_06_MYPAGE_TODAY));
        BaeminScreenParser.MyPageResult r = BaeminScreenParser.parseMyPage(BAEMIN_06_MYPAGE_TODAY);
        assertEquals(21, r.count);
        assertEquals(75450L, r.earnings);
    }

    @Test
    public void myPage_v1_18_41_loadingFlicker_zeroParsed() {
        // 0건/0원 으로 파싱된다 → 서비스의 (count > 0 && earnings > 0) 가드가 막는다
        BaeminScreenParser.MyPageResult r = BaeminScreenParser.parseMyPage(BAEMIN_V1_18_41_MYPAGE_LOADING_ZERO);
        assertEquals(0, r.count);
        assertEquals(0L, r.earnings);
    }

    @Test
    public void myPage_notMyPage_orNoNumbers() {
        assertFalse(BaeminScreenParser.isMyPageStats(BAEMIN_05_COMPLETION_MODAL_BUNDLE));
        assertFalse(BaeminScreenParser.isMyPageStats(texts("완료건수", "21건")));   // 마이페이지 타이틀 없음
        BaeminScreenParser.MyPageResult r = BaeminScreenParser.parseMyPage(texts("마이페이지", "완료건수", "수입", "배달내역"));
        assertEquals(-1, r.count);
        assertEquals(-1L, r.earnings);
    }

    // -------------------------------------------------------------
    // 완료 모달 누락 복구 (v1.18.40) — 진행 목록 주문번호 정규식
    // -------------------------------------------------------------

    @Test
    public void order_v1_18_40_storeLine_orderIdAndFee() {
        java.util.regex.Matcher m = BaeminScreenParser.ORDER_STORE_PATTERN.matcher(ORDER_V1_18_40_STORE_LINE);
        assertTrue(m.find());
        assertEquals("T0TEST0001", m.group(1));
        assertEquals("1,880", m.group(2));
    }

    @Test
    public void order_v1_18_40_dropLine_addressAndOrderId() {
        java.util.regex.Matcher m = BaeminScreenParser.ORDER_DROP_PATTERN.matcher(ORDER_V1_18_40_DROP_LINE);
        assertTrue(m.find());
        assertEquals("서울 가상구 샘플로 150 샘플병원", m.group(1));
        assertEquals("T0TEST0001", m.group(2));
        assertFalse(BaeminScreenParser.ORDER_DROP_PATTERN.matcher(ORDER_V1_18_40_STORE_LINE).find());
    }

    // -------------------------------------------------------------

    private static int modalAmount(String... t) {
        BaeminScreenParser.CompletionModalResult r = BaeminScreenParser.evaluateCompletionModal(texts(t));
        assertTrue("완료 모달이어야 함: " + java.util.Arrays.toString(t), r.isModal);
        return r.amount;
    }

    private static List<BaeminScreenParser.MissionLine> parseAll(List<String> texts) {
        List<BaeminScreenParser.MissionLine> out = new ArrayList<>();
        for (String t : texts) {
            BaeminScreenParser.MissionLine m = BaeminScreenParser.parseMissionLine(t, null);
            if (m != null) out.add(m);
        }
        return out;
    }
}
