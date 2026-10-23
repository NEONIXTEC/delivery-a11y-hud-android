package com.ridermeter.a11y;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static com.ridermeter.a11y.ScreenFixtures.*;
import static org.junit.Assert.*;

/**
 * 진단 로그 전송 전 가림 (DiagnosticRedactor) 회귀 테스트.
 * 두 가지를 같이 지킨다: ① 파서 디버깅에 필요한 금액, 건수, 화면 단어는 남는다 ② 주소, 전화번호, 주문번호, 요청사항은 기기 밖으로 안 나간다.
 * 픽스처: ScreenFixtures (실측 화면) + 합성 주소, 전화번호 줄.
 */
public class DiagnosticRedactorTest {

    private static String r(String s) {
        return DiagnosticRedactor.redactScreenText(s);
    }

    /** DeliveryAccessibilityService.logBaeminDump 와 같은 형식 */
    private static String dump(String trigger, List<String> texts, String eval) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n======================================================\n");
        sb.append("[2026-10-23 19:42:10.123] ").append(trigger).append("\n");
        sb.append("Total Texts (").append(texts.size()).append("):\n");
        for (int i = 0; i < texts.size(); i++) {
            sb.append(String.format(Locale.US, "  [%02d] %s\n", i, texts.get(i)));
        }
        if (eval != null) sb.append(">> Eval: ").append(eval).append("\n");
        sb.append("======================================================\n");
        return sb.toString();
    }

    private static void assertNoPii(String out) {
        for (String bad : Arrays.asList(
                "테스트대로", "9999", "샘플파크", "101동", "1203호", "1234", "5678", "벨 눌러",
                "B1A2C3D4E5", "2610241234567", "T0TEST0001", "9Z8TST", "샘플로", "123-4", "샘플이즈빌",
                "예시대로", "문 앞에", "샘플카페", "홍길동", "가상점", "샘플점", "숯불치킨, ")) {
            assertFalse("새면 안 되는 글자: " + bad + "\n" + out, out.contains(bad));
        }
    }

    // -------------------------------------------------------------
    // 1. 남아야 하는 줄 (파서 디버깅)
    // -------------------------------------------------------------

    @Test
    public void keeps_moneyAndCountLines() {
        assertEquals("배달료 3,900원", r("배달료 3,900원"));
        assertEquals("+3,900원", r("+3,900원"));
        assertEquals("1,350원", r("1,350원"));
        assertEquals("21건", r("21건"));
        assertEquals("75,450원", r("75,450원"));
        assertEquals("6,234₩", r("6,234₩"));
        assertEquals("성공! 1만 5천원 보너스", r("성공! 1만 5천원 보너스"));
        assertEquals("2건 모두 수락 · 30초", r("2건 모두 수락 · 30초"));
    }

    @Test
    public void keeps_missionLinesVerbatim() {
        assertEquals(MISSION_V1_18_28_PROGRESS, r(MISSION_V1_18_28_PROGRESS));
        assertEquals(MISSION_V1_18_28_NO_PROGRESS, r(MISSION_V1_18_28_NO_PROGRESS));
        assertEquals(MISSION_V1_18_50_SCHEDULED, r(MISSION_V1_18_50_SCHEDULED));
    }

    @Test
    public void keeps_whitelistedScreenWords() {
        for (String w : Arrays.asList("배달완료", "배달 이어서 하기", "확인", "닫기", "마이페이지", "오늘", "어제", "미션",
                "신규배차", "신규배차 켜기", "픽업", "픽업 완료", "전달완료", "전달 완료", "가게 도착", "배달 요청사항",
                "이미 전달완료된 배달입니다.", "일시적인 오류가 발생했어요", "픽업지로 이동해주세요", "진행 배달미션")) {
            assertEquals(w, r(w));
        }
        assertEquals("신규배차를 켜고 배달을 시작하세요!", r("신규배차를 켜고 배달을 시작하세요!"));
        assertEquals("__TODAY_SELECTED__", r("__TODAY_SELECTED__"));
        assertEquals("__YESTERDAY_SELECTED__", r("__YESTERDAY_SELECTED__"));
    }

    @Test
    public void keeps_shortLabelsTimesAndDistances() {
        assertEquals("배달 완료!", r("배달 완료!"));
        assertEquals("22:00~22:59", r("22:00~22:59"));
        assertEquals("6/17 ~ 6/23", r("6/17 ~ 6/23"));
        assertEquals("배달거리 2.8km (실제경로)", r("배달거리 2.8km (실제경로)"));
        assertEquals("1200P 남음", r("1200P 남음"));
        assertEquals("18:19 완료", r("18:19 완료"));
    }

    @Test
    public void everyParserFixtureLine_keepsAllMoneyAndCounts() {
        // 금액, 건수가 있는 줄은 (주소가 섞이지 않았다면) 하나도 [가림] 이 되면 안 된다
        List<List<String>> all = Arrays.asList(
                BAEMIN_01_OFFER_BATCH, BAEMIN_05_COMPLETION_MODAL_BUNDLE, BAEMIN_06_MYPAGE_TODAY,
                BAEMIN_V1_18_50_HOME_SHEET_MULTI_MISSION, BAEMIN_V1_18_26_PICKUP_WAIT_POPUP_MENU_PRICE,
                COUPANG_02_CALL_OFFER, COUPANG_04_PICKUP_DONE, COUPANG_07_COMPLETION_MODAL_SSOT_SPLIT,
                COUPANG_08_MY_EARNINGS, COUPANG_V1_18_50_COMPLETION_MODAL_MISSION_SUCCESS);
        int checked = 0;
        for (List<String> texts : all) {
            for (String node : texts) {
                for (String line : node.split("\n")) {
                    if (!DiagnosticRedactor.MONEY.matcher(line).find() && !DiagnosticRedactor.COUNT.matcher(line).find()) continue;
                    if (line.contains("배달 완료")) continue;   // "[매장명] 배달 완료" 는 따로 본다
                    assertEquals(line, r(line));
                    checked++;
                }
            }
        }
        assertTrue(checked > 30);
    }

    @Test
    public void redactedFixtures_stillParse() {
        // 가린 덤프로도 파서가 같은 답을 내야 디버깅이 된다
        assertEquals(BaeminScreenParser.evaluateCompletionModal(BAEMIN_05_COMPLETION_MODAL_BUNDLE).isModal,
                BaeminScreenParser.evaluateCompletionModal(redactAll(BAEMIN_05_COMPLETION_MODAL_BUNDLE)).isModal);
        assertEquals(BaeminScreenParser.evaluateCompletionModal(BAEMIN_05_COMPLETION_MODAL_BUNDLE).parsedAmount,
                BaeminScreenParser.evaluateCompletionModal(redactAll(BAEMIN_05_COMPLETION_MODAL_BUNDLE)).parsedAmount);
        assertEquals(1350,
                BaeminScreenParser.evaluateCompletionModal(redactAll(BAEMIN_V1_18_53_COMPLETION_SPLIT_LABEL_1350)).parsedAmount);
        BaeminScreenParser.MyPageResult mp = BaeminScreenParser.parseMyPage(redactAll(BAEMIN_06_MYPAGE_TODAY));
        assertEquals(21, mp.count);
        assertEquals(75450L, mp.earnings);
        assertEquals(BaeminScreenParser.isOfferScreen(BAEMIN_01_OFFER_BATCH),
                BaeminScreenParser.isOfferScreen(redactAll(BAEMIN_01_OFFER_BATCH)));
    }

    private static List<String> redactAll(List<String> texts) {
        String[] out = new String[texts.size()];
        for (int i = 0; i < texts.size(); i++) out[i] = r(texts.get(i));
        return Arrays.asList(out);
    }

    // -------------------------------------------------------------
    // 2. 가려야 하는 줄 (개인정보)
    // -------------------------------------------------------------

    @Test
    public void masks_addresses() {
        assertEquals("[주소]", r("서울 가상구 테스트대로 9999 6층"));
        assertEquals("[주소]", r("샘플파크아파트 101동 1203호"));
        assertEquals("[주소]", r("가상구 예시로15가길"));
        assertEquals("[주소]", r("가상동 123-4 1층 104호(가상동, 샘플이즈빌)"));
        assertEquals("[주소]", r("서울특별시 가상구 예시대로 238 108호 샘플"));
        assertEquals("[주소]", r("서울 가상구 샘플로 150 샘플병원 정문 **** (길…"));
        assertEquals("[주소]", r("매장찾기 팁: 샘플씨티빌108호"));
    }

    @Test
    public void masks_phoneNumbers_withOrWithoutHyphens() {
        assertEquals("[전화번호]", r("050-1234-5678"));
        assertEquals("[전화번호]", r("0507 1234 5678"));
        assertEquals("[전화번호]", r("010-9876-5432"));
        assertEquals("[전화번호]", r("01098765432"));
        assertEquals("[전화번호]", r("02-123-4567"));
        assertEquals("[전화번호]", r("070-1234-5678"));
        assertEquals("안심번호 [전화번호]", r("안심번호 0504-123-4567"));
        assertEquals("[주민번호]", r("900101-1234567"));
    }

    @Test
    public void masks_orderNumbers() {
        assertEquals("[주문번호]", r("B1A2C3D4E5"));
        assertEquals("주문번호 [주문번호]", r("주문번호 2610241234567"));
        assertEquals("[번호]", r("2610241234567"));
        assertEquals("[주문번호]", r("9Z8TST"));
        assertEquals("주문 번호: [주문번호]", r("주문 번호: 9Z8TST"));
        // 가게 이름은 가리고, 주문번호 자리와 배달료는 남는다 (ORDER_STORE_PATTERN 디버깅용)
        assertEquals("[가림], [주문번호], 배달료 1,880원", r(ORDER_V1_18_40_STORE_LINE));
        assertEquals("[가림]", r(ORDER_V1_18_40_DROP_LINE));
    }

    @Test
    public void masks_requestsAndFreeText() {
        assertEquals("요청사항: [가림]", r("요청사항: 문 앞에 두고 벨 눌러주세요"));
        assertEquals("고객 요청: [가림]", r("고객 요청: \"샘플카페 옆 샛길로 들어오시면 빨라요! 벨X 문 앞에 놔주세요.\""));
        assertEquals("[가림]", r("문 앞에 두고 문자주세요(벨X, 노크X)"));
        assertEquals("[가림]", r("기본맛, 3찬반찬, 후기안해요, 등심돈…"));
    }

    @Test
    public void masks_namesAndStores() {
        assertEquals("[이름] 라이더님", r("홍길동 라이더님"));
        assertEquals("[가림]", r("샘플커피 가상점"));
        assertEquals("[가림]", r("ABC 샘플점"));
        assertEquals("[가림] 배달 완료", r("가나불닭 배달 완료"));
        assertEquals("[가림] 배달 완료", r("예시도시락 가상공도점 배달 완료"));
    }

    @Test
    public void generic_isIdempotent() {
        String once = DiagnosticRedactor.maskGeneric("서울 가상구 테스트대로 9999 6층 050-1234-5678 B1A2C3D4E5");
        assertEquals(once, DiagnosticRedactor.maskGeneric(once));
        assertEquals("[주소] [전화번호] [주문번호]", once);
    }

    // -------------------------------------------------------------
    // 3. 파일 단위 (덤프 / 이벤트 로그 / logcat)
    // -------------------------------------------------------------

    @Test
    public void dump_keepsStructureAndIndices() {
        String raw = dump("USER_CLICK: CLICK [BAEMIN]: text='배달 이어서 하기' cls=android.widget.Button",
                BAEMIN_05_COMPLETION_MODAL_BUNDLE, "COMPLETE fee=3900 bundle=true");
        String out = DiagnosticRedactor.redactDump(raw);
        String[] in = raw.split("\n", -1);
        String[] got = out.split("\n", -1);
        assertEquals(in.length, got.length);
        assertTrue(out.contains("[2026-10-23 19:42:10.123] USER_CLICK: CLICK [BAEMIN]: text='배달 이어서 하기' cls=android.widget.Button"));
        assertTrue(out.contains("Total Texts (16):"));
        assertTrue(out.contains("  [04] [주소]"));
        assertTrue(out.contains("  [13] 배달완료"));
        assertTrue(out.contains("  [14] 배달료 3,900원"));
        assertTrue(out.contains("  [15] 배달 이어서 하기"));
        assertTrue(out.contains(">> Eval: COMPLETE fee=3900 bundle=true"));
        assertNoPii(out);
    }

    @Test
    public void dump_multiLineNodesAndSyntheticPii() {
        List<String> texts = Arrays.asList(
                "신규배차",
                "서울 가상구 테스트대로 9999 6층",
                "샘플파크아파트 101동 1203호",
                "안심번호 050-1234-5678",
                "0507 1234 5678",
                "요청사항: 문 앞에 두고 벨 눌러주세요",
                "B1A2C3D4E5",
                "주문번호 2610241234567",
                "2,874원\n2P\n18:19 완료\n | \n 0.1km\nABC 샘플점\n가상구 예시로15가길\n다른 주문과 함께 배달한 건",
                ORDER_V1_18_40_STORE_LINE,
                "가나불닭 배달 완료\n4,000원",
                "배달료 3,900원");
        String out = DiagnosticRedactor.redactDump(dump("WINDOW_STATE_CHANGED", texts, null));
        assertNoPii(out);
        assertTrue(out.contains("  [01] [주소]"));
        assertTrue(out.contains("  [03] 안심번호 [전화번호]"));
        assertTrue(out.contains("  [05] 요청사항: [가림]"));
        assertTrue(out.contains("  [08] 2,874원\n2P\n18:19 완료\n | \n 0.1km\n[가림]\n[주소]\n"));
        assertTrue(out.contains("  [09] [가림], [주문번호], 배달료 1,880원"));
        assertTrue(out.contains("  [10] [가림] 배달 완료\n4,000원"));
        assertTrue(out.contains("  [11] 배달료 3,900원"));
    }

    @Test
    public void dump_cutInTheMiddle_isStrictFromTheFirstLine() {
        // 250줄 컷으로 헤더 없이 글자 줄부터 시작해도 공통 가림만 하지 않는다
        String raw = "  [07] 문 앞에 두고 벨 눌러주세요 꼭이요\n고객님 감사합니다 오늘도 수고하세요\n"
                + "======================================================\n";
        String out = DiagnosticRedactor.redactDump(raw);
        assertEquals("  [07] [가림]\n[가림]\n======================================================\n", out);
    }

    @Test
    public void dump_clickedTextInHeaderIsRedacted() {
        String raw = dump("USER_CLICK: CLICK [COUPANG]: text='서울 가상구 테스트대로 9999 6층' cls=android.widget.TextView",
                Arrays.asList("확인"), "User clicked on Coupang view");
        String out = DiagnosticRedactor.redactDump(raw);
        assertTrue(out, out.contains("USER_CLICK: CLICK [COUPANG]: text='[주소]' cls=android.widget.TextView"));
        assertNoPii(out);
    }

    @Test
    public void events_genericMasksAndClickText() {
        String raw = "[2026-10-23 19:42:10.123] [USER_CLICK   ] [BAEMIN ] 클릭 감지: text='문 앞에 두고 벨 눌러주세요 부탁드려요'\n"
                + "[2026-10-23 19:42:11.000] [USER_CLICK   ] [BAEMIN ] 클릭 감지: text='배달 이어서 하기'\n"
                + "[2026-10-23 19:42:12.000] [USER_CLICK   ] [COUPANG] 클릭 감지: text='050-1234-5678'\n"
                + "[2026-10-23 19:43:00.000] [RECOVER      ] [BAEMIN ] 완료모달 누락 복구('이미 전달완료'): order=T0TEST0001 fee=1880\n"
                + "[2026-10-23 19:44:00.000] [DELIVERY     ] [BAEMIN ] 배달완료 디스패치: 3900원 (bundle=true)\n";
        String out = DiagnosticRedactor.redactEvents(raw);
        assertTrue(out.contains("클릭 감지: text='[가림]'"));
        assertTrue(out.contains("클릭 감지: text='배달 이어서 하기'"));
        assertTrue(out.contains("클릭 감지: text='[전화번호]'"));
        assertTrue(out.contains("order=[주문번호] fee=1880"));
        assertTrue(out.contains("[2026-10-23 19:44:00.000] [DELIVERY     ] [BAEMIN ] 배달완료 디스패치: 3900원 (bundle=true)"));
        assertEquals(raw.split("\n").length, out.split("\n").length);
        assertNoPii(out);
    }

    @Test
    public void logcat_oldDumpLinesUseDumpRules_otherLinesGeneric() {
        String p = "10-23 19:42:10.123 I/DeliveryA11y(31337): ";
        String raw = p + "\n"
                + p + "======================================================\n"
                + p + "[2026-10-23 19:42:10.123] WINDOW_STATE_CHANGED\n"
                + p + "Total Texts (3):\n"
                + p + "  [00] 배달료 3,900원\n"
                + p + "  [01] 서울 가상구 테스트대로 9999 6층\n"
                + p + "  [02] 문 앞에 두고 벨 눌러주세요 부탁드려요\n"
                + p + "======================================================\n"
                + p + ">>> [배민 배달완료 팝업 포착!] 배달료: 3900원 (묶음 여부: true)\n"
                + "10-23 19:42:11.000 I/FloatingPill( 2222): show celebration 3900\n"
                + "10-23 19:42:12.000 E/AndroidRuntime( 3333): java.lang.IllegalStateException: 0507 1234 5678\n";
        String out = DiagnosticRedactor.redactLogcat(raw);
        assertTrue(out.contains(p + "  [00] 배달료 3,900원"));
        assertTrue(out.contains(p + "  [01] [주소]"));
        assertTrue(out.contains(p + "  [02] [가림]"));
        assertTrue(out.contains(p + ">>> [배민 배달완료 팝업 포착!] 배달료: 3900원 (묶음 여부: true)"));
        assertTrue(out.contains("10-23 19:42:11.000 I/FloatingPill( 2222): show celebration 3900"));
        assertTrue(out.contains("IllegalStateException: [전화번호]"));
        assertNoPii(out);
    }

    @Test
    public void nullAndEmptyAreSafe() {
        assertNull(DiagnosticRedactor.redactDump(null));
        assertNull(DiagnosticRedactor.redactEvents(null));
        assertNull(DiagnosticRedactor.redactLogcat(null));
        assertEquals("", DiagnosticRedactor.redactDump(""));
    }
}
