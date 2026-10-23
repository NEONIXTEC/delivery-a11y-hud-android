package com.ridermeter.a11y;

import org.junit.Ignore;
import org.junit.Test;

import java.util.List;

import static com.ridermeter.a11y.ScreenFixtures.*;
import static org.junit.Assert.*;

/**
 * 쿠팡 화면 흐름 회귀 테스트 (대기 → 제안 → 매장 도착 → 픽업 → 촬영 → 완료 모달 → 내 수입).
 * handleCoupang 판정 순서: 오퍼 필터 → 내 수입 동기화 → 완료 모달 → 배달 중 추적.
 * KNOWN ISSUE 테스트는 @Ignore — 바라는 동작을 적어 두었고, 지금 코드는 통과하지 못한다.
 */
public class CoupangScreenFlowTest {

    private static final List<List<String>> NON_OFFER_SCREENS = java.util.Arrays.asList(
        COUPANG_01_IDLE_BUBBLE, COUPANG_03_STORE_ARRIVAL, COUPANG_04_PICKUP_DONE,
        COUPANG_05_DROPOFF_PHOTO_BUTTON, COUPANG_06_PHOTO_COMPLETE_BUTTON,
        COUPANG_V1_18_50_COMPLETION_MODAL_MISSION_SUCCESS, COUPANG_07_COMPLETION_MODAL_SSOT_SPLIT,
        COUPANG_08_MY_EARNINGS
    );

    // -------------------------------------------------------------
    // 0. 오퍼 필터
    // -------------------------------------------------------------

    @Test
    public void offer_coupang02_callOffer_isOffer() {
        assertTrue(CoupangScreenParser.isOfferScreen(COUPANG_02_CALL_OFFER));
    }

    @Test
    public void offer_otherScreens_notOffer() {
        for (List<String> s : NON_OFFER_SCREENS) {
            assertFalse(s.toString(), CoupangScreenParser.isOfferScreen(s));
        }
    }

    @Test
    public void offer_pickupLegalText_doesNotTripFilter() {
        // "배달을 수락한 시점부터" → "배달을수락한" 은 contains("배달수락") 가 아니다
        assertFalse(CoupangScreenParser.isOfferScreen(texts("배달 및 배달관련 CS 업무처리를 위해 배달을 수락한 시점부터 완료시까지")));
    }

    // -------------------------------------------------------------
    // 배달 중 추적 (내 수입 조기 동기화 차단용)
    // -------------------------------------------------------------

    @Test
    public void inFlight_storeArrival_pickup_dropoff_photo() {
        assertTrue(CoupangScreenParser.isInFlightScreen(COUPANG_03_STORE_ARRIVAL));
        assertTrue(CoupangScreenParser.isInFlightScreen(COUPANG_04_PICKUP_DONE));
        assertTrue(CoupangScreenParser.isInFlightScreen(COUPANG_05_DROPOFF_PHOTO_BUTTON));
        assertTrue(CoupangScreenParser.isInFlightScreen(COUPANG_06_PHOTO_COMPLETE_BUTTON));
    }

    @Test
    public void inFlight_idle_offer_modal_earnings_false() {
        assertFalse(CoupangScreenParser.isInFlightScreen(COUPANG_01_IDLE_BUBBLE));
        assertFalse(CoupangScreenParser.isInFlightScreen(COUPANG_02_CALL_OFFER));
        assertFalse(CoupangScreenParser.isInFlightScreen(COUPANG_V1_18_50_COMPLETION_MODAL_MISSION_SUCCESS));
        assertFalse(CoupangScreenParser.isInFlightScreen(COUPANG_08_MY_EARNINGS));
    }

    // -------------------------------------------------------------
    // 1. 완료 모달
    // -------------------------------------------------------------

    @Test
    public void modal_nonModalScreens_rejected() {
        assertFalse(CoupangScreenParser.parseCompletionModal(COUPANG_01_IDLE_BUBBLE).isModal);
        assertFalse(CoupangScreenParser.parseCompletionModal(COUPANG_03_STORE_ARRIVAL).isModal);
        // 픽업 화면 음식값/주문금액 13,500원 → 완료 아님
        assertFalse(CoupangScreenParser.parseCompletionModal(COUPANG_04_PICKUP_DONE).isModal);
        assertFalse(CoupangScreenParser.parseCompletionModal(COUPANG_05_DROPOFF_PHOTO_BUTTON).isModal);
        assertFalse(CoupangScreenParser.parseCompletionModal(COUPANG_08_MY_EARNINGS).isModal);
    }

    @Test
    public void modal_coupang06_photoScreen_completeButton_rejectedByPhotoGuard() {
        // '배달 완료' 버튼 글자가 타이틀 규칙(endsWith 배달완료)에 맞지만, 화면의 '사진'/'촬영' 가드가 먼저 막는다
        assertFalse(CoupangScreenParser.parseCompletionModal(COUPANG_06_PHOTO_COMPLETE_BUTTON).isModal);
        assertFalse(CoupangScreenParser.parseCompletionModal(texts("05 배달 완료", "사진 다시 촬영", "확인")).isModal);
    }

    @Test
    public void modal_v1_18_50_realDumpFormat_4000_missionSuccess15000() {
        CoupangScreenParser.CompletionModalResult r = CoupangScreenParser.parseCompletionModal(COUPANG_V1_18_50_COMPLETION_MODAL_MISSION_SUCCESS);
        assertTrue(r.isModal);
        assertEquals(4000, r.amount);
        CoupangScreenParser.ModalMissionResult mm = CoupangScreenParser.parseModalMission(COUPANG_V1_18_50_COMPLETION_MODAL_MISSION_SUCCESS);
        assertTrue(mm.success);
        assertEquals(15000L, mm.bonus);
        assertEquals(3, mm.target);
    }

    @Test
    public void modal_v1_18_50_missionInProgress_notAdded() {
        CoupangScreenParser.CompletionModalResult r = CoupangScreenParser.parseCompletionModal(COUPANG_V1_18_50_COMPLETION_MODAL_MISSION_PROGRESS);
        assertTrue(r.isModal);
        assertEquals(4560, r.amount);
        CoupangScreenParser.ModalMissionResult mm = CoupangScreenParser.parseModalMission(COUPANG_V1_18_50_COMPLETION_MODAL_MISSION_PROGRESS);
        assertFalse(mm.success);
        assertEquals(2, mm.remaining);
        assertEquals(15000L, mm.bonus);
    }

    @Test
    public void modal_noConfirmButton_notModal() {
        assertFalse(CoupangScreenParser.parseCompletionModal(texts("가나불닭 배달 완료\n4,000원", "기본료\n4,000원")).isModal);
    }

    @Test
    public void modal_amountNotFound_failClosedZero() {
        CoupangScreenParser.CompletionModalResult r = CoupangScreenParser.parseCompletionModal(texts("가나불닭 배달 완료", "확인"));
        assertTrue(r.isModal);
        assertEquals(0, r.amount);
    }

    @Test
    public void modal_coupang07_ssotSplitNodes_detectedAsModal() {
        assertTrue(CoupangScreenParser.parseCompletionModal(COUPANG_07_COMPLETION_MODAL_SSOT_SPLIT).isModal);
    }

    @Ignore("KNOWN ISSUE (미확인 형식): 타이틀과 총액이 다른 노드면 금액 0원 — 상세가 '기본 수수료 3,250원'이라 '기본료/배달료' 보조 파싱도 못 탄다")
    @Test
    public void knownIssue_modal_coupang07_ssotSplitNodes_amount6234() {
        // 현재: amount=0 (fail-closed), 바라는 값: 6,234원 (타이틀 바로 다음 단독 금액 노드)
        assertEquals(6234, CoupangScreenParser.parseCompletionModal(COUPANG_07_COMPLETION_MODAL_SSOT_SPLIT).amount);
    }

    @Test
    public void mission_coupang07_stagedSuccessWording_notDetected_currentBehavior() {
        // 현재 동작 고정: SSOT 2.7 의 단계형 문구 "1차 성공! 5천원 확보" 는 '원 보너스' 가 아니라 성공으로 안 읽는다.
        // 10/18 실측 문구는 "성공! 1만 5천원 보너스". 단계형 미션을 더해야 하는지는 아직 확인 전.
        CoupangScreenParser.ModalMissionResult mm = CoupangScreenParser.parseModalMission(COUPANG_07_COMPLETION_MODAL_SSOT_SPLIT);
        assertFalse(mm.success);
        assertEquals(0L, mm.bonus);
    }

    @Test
    public void koreanWon_units() {
        assertEquals(15000L, CoupangScreenParser.parseKoreanWon("1만 5천원"));
        assertEquals(15000L, CoupangScreenParser.parseKoreanWon("1만5천원"));
        assertEquals(5000L, CoupangScreenParser.parseKoreanWon("5천원"));
        assertEquals(15000L, CoupangScreenParser.parseKoreanWon("15,000원"));
        assertEquals(7500L, CoupangScreenParser.parseKoreanWon("7천 5백원"));
        assertEquals(0L, CoupangScreenParser.parseKoreanWon(null));
        assertEquals(0L, CoupangScreenParser.parseKoreanWon("보너스"));
    }

    // -------------------------------------------------------------
    // 0. 내 수입 동기화 (coupang_08)
    // -------------------------------------------------------------

    @Test
    public void earnings_coupang08_0619_21건_98138_mission6000() {
        CoupangScreenParser.EarningsSyncResult r = CoupangScreenParser.parseEarningsSync(COUPANG_08_MY_EARNINGS, "06/19");
        assertTrue(r.valid);
        assertEquals(21, r.count);
        assertEquals(98138L, r.fee);
        assertEquals(6000L, r.missionBonus);
        assertEquals(104138L, r.total);
    }

    @Test
    public void earnings_coupang08_0620_noMission() {
        CoupangScreenParser.EarningsSyncResult r = CoupangScreenParser.parseEarningsSync(COUPANG_08_MY_EARNINGS, "06/20");
        assertTrue(r.valid);
        assertEquals(55, r.count);
        assertEquals(269095L, r.fee);
        assertEquals(0L, r.missionBonus);
    }

    @Test
    public void earnings_weekTotalNotUsed_whenTodayRowMissing() {
        // 주간 총 수입(347,663원 / 76건)은 날짜가 없어 오늘 실적으로 쓰지 않는다
        assertFalse(CoupangScreenParser.parseEarningsSync(COUPANG_08_MY_EARNINGS, "06/21").valid);
    }

    @Test
    public void earnings_nonEarningsScreens_invalid() {
        assertFalse(CoupangScreenParser.parseEarningsSync(COUPANG_01_IDLE_BUBBLE, "06/19").valid);
        assertFalse(CoupangScreenParser.parseEarningsSync(COUPANG_V1_18_50_COMPLETION_MODAL_MISSION_SUCCESS, "06/19").valid);
    }
}
