package com.ridermeter.a11y;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class CoupangScreenParserTest {

    @Test
    public void testEarningsSync_today_golden() {
        List<String> dump = Arrays.asList(
            "내 수입",
            "10/7 ~ 10/13",
            "총 수입\n41,746원\n배달 6건\n자세히 보기",
            "일별 배달 수수료",
            "10/09 금  배달 1건\n19,087원",
            "10/11 일  배달 5건\n15,159원",
            "미션 보너스\n10/11 일 [지역 한정] 3건에 7,500원 깜짝 보너스!\n7,500원",
            "추가 수입 최대 10,000원 더 받기\n친구 초대 시\n10,000원"
        );

        CoupangScreenParser.EarningsSyncResult result =
            CoupangScreenParser.parseEarningsSync(dump, "10/11");

        assertTrue("내 수입 파싱 성공이어야 함", result.valid);
        assertEquals("배달 건수는 5건이어야 함", 5, result.count);
        assertEquals("배달료는 15,159원이어야 함 (친구초대 10,000원 오파싱 방어)", 15159L, result.fee);
        assertEquals("깜짝 미션 보너스는 7,500원이어야 함", 7500L, result.missionBonus);
        assertEquals("당일 쿠팡 총합은 22,659원이어야 함", 22659L, result.total);
    }

    @Test
    public void testEarningsSync_differentDate_returnsInvalid() {
        List<String> dump = Arrays.asList(
            "내 수입",
            "10/09 금  배달 1건\n19,087원",
            "추가 수입 최대 10,000원 더 받기\n친구 초대 시\n10,000원"
        );

        // 요청 날짜는 10/11인데 화면엔 10/09만 있는 경우
        CoupangScreenParser.EarningsSyncResult result =
            CoupangScreenParser.parseEarningsSync(dump, "10/11");

        assertFalse("당일 기록이 없으면 invalid이어야 함", result.valid);
    }

    @Test
    public void testCompletionModal_shinhwa_golden() {
        List<String> dump = Arrays.asList(
            "가나불닭 배달 완료\n4,000원",
            "기본료\n4,000원",
            "진행한 미션 1개",
            "확인"
        );

        CoupangScreenParser.CompletionModalResult result =
            CoupangScreenParser.parseCompletionModal(dump);

        assertTrue("완료 모달로 감지되어야 함", result.isModal);
        assertEquals("배달료는 4,000원이어야 함", 4000, result.amount);
    }

    @Test
    public void testCompletionModal_pizzaBogam_golden() {
        List<String> dump = Arrays.asList(
            "다라피자 가상구 본점 배달 완료\n2,537원",
            "기본료\n2,537원",
            "진행한 미션 1개\n...! 7,500원 보너스\n3건 완료",
            "58%",
            "미션 더 알아보기",
            "닫기"
        );

        CoupangScreenParser.CompletionModalResult result =
            CoupangScreenParser.parseCompletionModal(dump);

        assertTrue("완료 모달로 감지되어야 함", result.isModal);
        assertEquals("배달료는 2,537원이어야 함", 2537, result.amount);
    }

    @Test
    public void testCompletionModal_photoScreen_rejected() {
        List<String> dump = Arrays.asList(
            "배달 완료 사진은 고객님과 안전을 위해 전달됩니다",
            "사진 다시 촬영",
            "배달 완료",
            "확인"
        );

        CoupangScreenParser.CompletionModalResult result =
            CoupangScreenParser.parseCompletionModal(dump);

        assertFalse("촬영 안내 화면은 완료 모달로 감지되면 안 됨", result.isModal);
    }

    @Test
    public void testDeliveryHistory_golden() {
        List<String> dump = Arrays.asList(
            "10/11 일",
            "2,874원\n2P\n18:19 완료\n | \n 0.1km\nABC 샘플점\n가상구 예시로15가길\n다른 주문과 함께 배달한 건",
            "2,874원\n10P\n18:22 완료\n | \n 0.9km\n가나쌀국수 예시점\n가상구 예시로12길\n다른 주문과 함께 배달한 건",
            "2,537원\n9P\n18:37 완료\n | \n 0.9km\n다라피자 가상구 본점\n가상구 샘플로25길\n다른 주문과 함께 배달한 건",
            "2,874원\n4P\n18:41 완료\n | \n 0.5km\n마바오므라이스 예시본점\n가상구 테스트대로175길\n다른 주문과 함께 배달한 건"
        );

        List<CoupangScreenParser.HistoryOrderItem> items =
            CoupangScreenParser.parseDeliveryHistory(dump);

        assertEquals("주문 4건이 파싱되어야 함", 4, items.size());
        assertEquals("18:19", items.get(0).time);
        assertEquals("ABC 샘플점", items.get(0).storeName);
        assertEquals(2874, items.get(0).amount);

        assertEquals("18:22", items.get(1).time);
        assertEquals("가나쌀국수 예시점", items.get(1).storeName);
        assertEquals(2874, items.get(1).amount);

        assertEquals("18:37", items.get(2).time);
        assertEquals("다라피자 가상구 본점", items.get(2).storeName);
        assertEquals(2537, items.get(2).amount);

        assertEquals("18:41", items.get(3).time);
        assertEquals("마바오므라이스 예시본점", items.get(3).storeName);
        assertEquals(2874, items.get(3).amount);
    }
}
