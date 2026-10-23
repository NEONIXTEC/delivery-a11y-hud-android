package com.ridermeter.a11y;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [Module Spec]
 * - Anchor: 진단 로그 전송 전 기기 안 가림 (RiderMeter 앱의 진단 로그 업로더가 보내기 직전에 부른다. 이 엔진 자체는 아무것도 보내지 않는다)
 * - Pipeline I/O: Input: baemin/coupang UI 덤프, delivery_events.log, logcat 원문 -> Output: 가린 문자열
 * - Constraints: Pure Java (android.* 금지. JVM 단위 테스트 `parser-tests.sh`).
 *                기기 안 파일(덤프)은 그대로 둔다. 기기 밖으로 나가는 것만 가린다.
 *
 * 덤프 글자 줄 (화면 노드 텍스트) 규칙:
 *   1) 공통 가림 먼저: 전화번호(01x/050x/0x0), 주민번호, 주문번호(영숫자 6자 이상, 숫자 8자리 이상),
 *      "주문번호 ..." 뒤 값, "요청사항: ..." 뒤 내용, "OOO 라이더님" 이름, 주소(시/구/동/로/길 + 번호, 동/호/층, 아파트, 빌라 이름).
 *   2) 주소가 섞인 줄은 통째로 [주소].
 *   3) 남기는 줄: 파서가 아는 화면 단어(WHITELIST), 금액(3,900원), 건수(13건), 짧은 줄(12자 이하, 숫자 2개 이하,
 *      "OO점" 같은 가게 지점 이름은 제외). "[매장명] 배달 완료" 는 매장명만 가린다.
 *   4) 나머지는 [가림]. 줄 번호([05])는 남겨서 화면 구조는 보이게 한다.
 * 이벤트 로그, logcat 은 공통 가림만 하고, 클릭 글자(text='...')는 덤프 글자 줄 규칙으로 가린다.
 * RiderMeter 서버도 같은 공통 가림을 한 번 더 한다. 정규식을 바꾸면 양쪽을 같이 바꿀 것.
 */
public final class DiagnosticRedactor {
    private DiagnosticRedactor() {}

    /** 서버가 어느 가림 규칙을 거친 번들인지 알 수 있게 같이 보낸다 */
    public static final String VERSION = "device-v1";

    static final String MASK_LINE = "[가림]";
    static final String MASK_ADDR = "[주소]";
    static final String MASK_PHONE = "[전화번호]";
    static final String MASK_ORDER = "[주문번호]";
    static final String MASK_NUMBER = "[번호]";
    static final String MASK_RRN = "[주민번호]";
    static final String MASK_NAME = "[이름]";

    static final int SHORT_LINE_MAX = 12;
    static final int SHORT_LINE_MAX_DIGITS = 2;

    // ---- 공통 가림 정규식 (RiderMeter 서버 쪽 가림 규칙과 같은 뜻으로 유지) ----
    // 하이픈 없는 13자리는 LONG_DIGITS 가 [번호] 로 가린다 (주문번호와 구분 안 되니 이름표를 주민번호로 달지 않는다)
    static final Pattern RRN = Pattern.compile("(?<![0-9])[0-9]{6}-[1-4][0-9]{6}(?![0-9])");
    // 010-1234-5678, 01012345678, 050-1234-5678, 0507 1234 5678, 02-123-4567, 070-1234-5678, +82 10-1234-5678
    static final Pattern PHONE = Pattern.compile("(?:\\+82[-\\s]?0?|(?<![0-9])0)[0-9]{1,3}[-.\\s]?[0-9]{3,4}[-.\\s]?[0-9]{4}(?![0-9])");
    static final Pattern ORDER_LABELED = Pattern.compile("(주문\\s*번호\\s*[:：]?\\s*)[A-Za-z0-9-]{4,}");
    // 배민 T0TEST0001, 쿠팡 9Z8TST 처럼 대문자와 숫자가 섞인 6자 이상
    static final Pattern ORDER_CODE = Pattern.compile("(?<![A-Za-z0-9])(?=[A-Z0-9]*[0-9])(?=[A-Z0-9]*[A-Z])[A-Z0-9]{6,}(?![A-Za-z0-9])");
    static final Pattern LONG_DIGITS = Pattern.compile("(?<![0-9])[0-9]{8,}(?![0-9])");
    // "가게, [주문번호], 배달료 1,880원" 의 가게 이름 / "전달지 주소, [주문번호], 도착예상시간" 의 주소
    static final Pattern BEFORE_ORDER = Pattern.compile("[^,\\[\\]'\"=:]+,(\\s*)(?=\\[주문번호\\])");
    static final Pattern RIDER_NAME = Pattern.compile("(?<![가-힣])[가-힣]{2,4}(\\s*)(?=라이더님)");
    static final Pattern REQUEST = Pattern.compile("((?:요청\\s*사항|요청|메모)\\s*[:：])\\s*\\S.*$");

    // 주소 단서. 찾으면 앞뒤로 이어진 글자 덩어리까지 [주소] 로 넓힌다.
    private static final String NOT_UNIT = "(?![0-9.,]*\\s*(?:건|원|개|분|초|km|m|%|시간|차|명|일|월|P))";
    static final Pattern[] ADDRESS = {
        // 테스트대로 9999, 예시로15가길, 샘플로 150
        Pattern.compile("[가-힣A-Za-z0-9]*[가-힣](?:로|길)\\s?[0-9]{1,5}(?:-[0-9]{1,5})?" + NOT_UNIT),
        // 101동, 1203호, 6층, 349번지 (1호점, 2호선 은 제외)
        Pattern.compile("(?<![0-9,.])(?:[A-Za-z]|지하)?[0-9]{1,5}(?:-[0-9]{1,5})?\\s?(?:동|호|층|번지)(?!점|선|차|기)"),
        // 가상동 123-4, 역삼동 123
        Pattern.compile("[가-힣]+(?:동|리)\\s?[0-9]{1,5}(?:-[0-9]{1,5})?" + NOT_UNIT),
        // 가상구 샘플로, 가상구 가상동
        Pattern.compile("[가-힣]{1,6}(?:시|구|군)\\s[가-힣0-9]{1,10}(?:동|읍|면|리|로|길)(?![가-힣])"),
        // 샘플이즈빌, OO파크아파트, OO오피스텔
        Pattern.compile("[가-힣A-Za-z0-9]*(?:아파트|APT|빌라|맨션|오피스텔|빌딩|타워|하이츠|캐슬|자이|푸르지오|래미안|아이파크|힐스테이트|더샵|편한세상|주공|레지던스|빌)(?![가-힣])"),
    };

    // ---- 덤프 글자 줄 판정 ----
    static final Pattern MONEY = Pattern.compile("[0-9][0-9,]*\\s*(?:만|천)?\\s*(?:원|₩)|₩\\s*[0-9]");
    static final Pattern COUNT = Pattern.compile("[0-9]+\\s*건");
    // 짧은 줄 길이를 잴 때 빼는 것: 시간, 날짜, 거리, 포인트, 퍼센트, 가림 토큰
    static final Pattern NEUTRAL = Pattern.compile(
            "[0-9]{1,2}:[0-9]{2}|[0-9]{1,2}\\s*월\\s*[0-9]{1,2}\\s*일|[0-9]{1,2}/[0-9]{1,2}"
            + "|[0-9]+(?:\\.[0-9]+)?\\s*k?m(?![A-Za-z])|[0-9]+\\s*(?:P|%|초|분|개)(?![A-Za-z])"
            + "|\\[(?:가림|주소|전화번호|주문번호|번호|주민번호|이름)\\]");
    // 가게 지점 이름 ("샘플커피 가상점", "ABC 샘플점"). 짧은 줄이라도 가린다 (라이더 동선이 드러난다)
    static final Pattern STORE_BRANCH = Pattern.compile("[가-힣A-Za-z0-9]{2,}점(?![가-힣])");
    // 쿠팡 완료 타이틀 "[매장명] 배달 완료" → 매장명만 가린다
    static final Pattern STORE_TITLE = Pattern.compile("^(.*\\S)\\s+(배달\\s?완료!?)$");

    // 파서(BaeminScreenParser / CoupangScreenParser / DeliveryAccessibilityService)가 보는 화면 단어. 공백 빼고 비교.
    static final Set<String> WHITELIST = normalizedSet(
        "배달완료", "배달 완료", "배달완료!", "배달 완료!", "배달을 완료했어요", "배달이 완료되었습니다", "배달완료했습니다",
        "배달 이어서 하기", "배달 계속하기", "이어서 배달하기", "이어서 진행하기", "신규배차 켜기",
        "확인", "닫기", "취소", "아니오", "선택됨", "완료", "진행", "대기", "지도", "주의", "보너스",
        "마이페이지", "오늘", "어제", "오늘의기록", "완료건수", "수입", "내 수입", "배달수입", "배달내역", "일별 배달 수수료",
        "미션", "배달미션", "미션 보너스", "진행중 미션", "진행 배달미션", "예정 배달미션", "미션 내역",
        "신규배차", "신규배달", "배차요청", "배차수락", "배달수락", "주문수락", "주문 수락", "모두수락", "모두거절", "모두 거절",
        "수락", "거절", "배차받기", "배차를 찾고 있어요", "배차대기중", "가까운배차", "가까운 주문을 찾는 중", "다음배달", "새로운배달",
        "픽업", "픽업완료", "픽업 완료", "픽업완료하기", "픽업 완료 되었습니다",
        "가게 도착", "가게도착", "가게에 도착하셨나요?", "매장 도착", "매장도착하기",
        "픽업지로 이동해주세요", "전달지로 이동해주세요", "배달지로 이동해주세요",
        "전달완료", "전달 완료", "이미 전달완료된 배달입니다.", "일시적인 오류가 발생했어요",
        "배달료", "중개배달", "중개배달료", "기본배달료", "기본료", "기본 수수료", "배달비", "주문금액", "음식금액", "메뉴금액", "결제금액",
        "촬영", "사진", "인증사진", "사진 다시 촬영", "사진 다시 찍기", "배달상태 촬영", "배달완료 후 인증사진", "촬영이 어려우신가요",
        "조리완료", "조리완료예정", "조리완료예상", "고객요청사항", "고객 요청사항", "배달 요청사항", "가게전화", "가게정보",
        "운행시작", "운행종료", "운행종료됨",
        // collectTexts 가 넣는 탭 선택 표시 (마이페이지 오늘/어제 판정)
        "__TODAY_SELECTED__", "__YESTERDAY_SELECTED__"
    );
    // 이 말로 시작하는 긴 안내문은 앞부분이 파서 단서라 통째로 남긴다 (공통 가림은 거친다)
    static final String[] WHITELIST_PREFIXES = normalizedArray(
        "배달 완료 사진은 고객님과", "상품과 전달지가", "대기배달을", "배차를 찾고 있어요", "신규배차를 켜고"
    );

    // ---- 덤프 구조 ----
    static final Pattern DUMP_ITEM = Pattern.compile("^(\\s*\\[[0-9]+\\] ?)(.*)$");
    static final Pattern DUMP_HEADER = Pattern.compile("^\\[[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:.]+\\] .*$");
    static final Pattern CLICK_TEXT = Pattern.compile("text='(.*)'");
    // logcat -v time: "10-23 12:34:56.789 I/DeliveryA11y( 1234): 메시지"
    static final Pattern LOGCAT_PREFIX = Pattern.compile("^([0-9]{2}-[0-9]{2} [0-9:.]+\\s+[VDIWEFA]/([^(:]+?)\\s*\\(\\s*[0-9]+\\):\\s?)(.*)$");
    static final String DUMP_TAG = "DeliveryA11y";

    // =================================================================
    // 공개 진입점
    // =================================================================

    /** baemin_ui_dump.txt / coupang_ui_dump.txt 끝부분 */
    public static String redactDump(String dump) {
        if (dump == null) return null;
        // 잘린 앞부분(250줄 컷)은 글자 줄 한가운데일 수 있어서 처음엔 글자 줄로 본다
        DumpState st = new DumpState(true, true);
        StringBuilder sb = new StringBuilder(dump.length());
        for (String line : splitLines(dump)) {
            sb.append(st.redact(line)).append('\n');
        }
        return trimTrailingNewlineLike(dump, sb);
    }

    /** delivery_events.log 끝부분 */
    public static String redactEvents(String events) {
        if (events == null) return null;
        StringBuilder sb = new StringBuilder(events.length());
        for (String line : splitLines(events)) {
            sb.append(redactLogLine(line)).append('\n');
        }
        return trimTrailingNewlineLike(events, sb);
    }

    /** logcat (예전 버전이 덤프를 logcat 에도 찍었으므로 DeliveryA11y 줄은 덤프 규칙으로) */
    public static String redactLogcat(String logcat) {
        if (logcat == null) return null;
        // 지금 릴리스는 덤프를 logcat 에 안 찍는다. 예전 버전이 남긴 덤프는 "[NN] 글자" 줄을 보고 가린다.
        DumpState st = new DumpState(false, false);
        StringBuilder sb = new StringBuilder(logcat.length());
        for (String line : splitLines(logcat)) {
            Matcher m = LOGCAT_PREFIX.matcher(line);
            if (m.matches()) {
                String tag = m.group(2).trim();
                String msg = m.group(3);
                sb.append(m.group(1)).append(DUMP_TAG.equals(tag) ? st.redact(msg) : redactLogLine(msg));
            } else {
                sb.append(redactLogLine(line));
            }
            sb.append('\n');
        }
        return trimTrailingNewlineLike(logcat, sb);
    }

    /** 덤프 글자 한 줄 (노드 텍스트) */
    public static String redactScreenText(String text) {
        if (text == null) return null;
        if (text.trim().isEmpty()) return text;
        String m = maskGeneric(text);
        if (m.contains(MASK_ADDR)) return MASK_ADDR;
        String ns = normalize(m);
        if (WHITELIST.contains(ns)) return m;
        for (String p : WHITELIST_PREFIXES) {
            if (ns.startsWith(p)) return m;
        }
        Matcher t = STORE_TITLE.matcher(m.trim());
        if (t.matches() && !WHITELIST.contains(normalize(t.group(1)))) {
            return MASK_LINE + " " + t.group(2);
        }
        if (MONEY.matcher(m).find() || COUNT.matcher(m).find()) {
            // 쉼표로 이어진 목록 줄("가게 지점, [가림], [주문번호], 배달료 2,450원")은 칸마다 따진다.
            // 금액이 있다고 줄을 통째로 남기면 맨 앞 가게 지점명이 샌다 (2026-10-24 실기기 덤프)
            return FIELD_SEP.matcher(m).find() ? redactFields(m) : m;
        }
        if (STORE_BRANCH.matcher(m).find()) return MASK_LINE;
        String residual = NEUTRAL.matcher(m).replaceAll("").trim().replaceAll("\\s+", " ");
        if (residual.codePointCount(0, residual.length()) <= SHORT_LINE_MAX
                && countDigits(residual) <= SHORT_LINE_MAX_DIGITS) {
            return m;
        }
        return MASK_LINE;
    }

    // 칸 구분 쉼표. 금액 안의 쉼표("2,450원", "11,000원")는 칸 구분이 아니다
    static final Pattern FIELD_SEP = Pattern.compile(",(?![0-9]{3}(?![0-9]))");
    // 목록 줄의 칸에서 남겨도 되는 말 (숫자, 시간, 가림 토큰을 뺀 나머지가 이것뿐이면 남긴다)
    static final Set<String> FIELD_WORDS = normalizedSet("오늘", "내일", "예정", "도착예상시간", "배달료", "기본료", "총", "합계", "미션");
    private static final Pattern FIELD_FILLER = Pattern.compile("[0-9\\s/~:.,+\\-()]");

    /** 쉼표 목록 줄: 금액, 건수, 시간, 화면 단어, 가림 토큰 칸만 남기고 나머지 칸(가게명, 주소 등)은 [가림] */
    static String redactFields(String line) {
        String[] parts = FIELD_SEP.split(line, -1);
        StringBuilder sb = new StringBuilder(line.length());
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(',');
            String f = parts[i];
            int lead = 0;
            while (lead < f.length() && Character.isWhitespace(f.charAt(lead))) lead++;
            sb.append(f, 0, lead).append(keepField(f.trim()) ? f.substring(lead) : MASK_LINE);
        }
        return sb.toString();
    }

    private static boolean keepField(String t) {
        if (t.isEmpty()) return true;
        if (t.matches("\\[[^\\]]+\\]")) return true;
        String ns = normalize(t);
        if (WHITELIST.contains(ns)) return true;
        if (MONEY.matcher(t).find() || COUNT.matcher(t).find()) return !STORE_BRANCH.matcher(t).find();
        String residual = FIELD_FILLER.matcher(NEUTRAL.matcher(t).replaceAll("")).replaceAll("");
        return residual.isEmpty() || FIELD_WORDS.contains(normalize(residual)) || WHITELIST.contains(normalize(residual));
    }

    /** 공통 가림: 화면 단어 판정 없이 번호, 주소, 요청사항만 */
    public static String maskGeneric(String s) {
        if (s == null || s.isEmpty()) return s;
        s = ORDER_LABELED.matcher(s).replaceAll("$1" + Matcher.quoteReplacement(MASK_ORDER));
        s = RRN.matcher(s).replaceAll(Matcher.quoteReplacement(MASK_RRN));
        s = PHONE.matcher(s).replaceAll(Matcher.quoteReplacement(MASK_PHONE));
        s = ORDER_CODE.matcher(s).replaceAll(Matcher.quoteReplacement(MASK_ORDER));
        s = LONG_DIGITS.matcher(s).replaceAll(Matcher.quoteReplacement(MASK_NUMBER));
        s = BEFORE_ORDER.matcher(s).replaceAll(Matcher.quoteReplacement(MASK_LINE) + ",$1");
        s = RIDER_NAME.matcher(s).replaceAll(Matcher.quoteReplacement(MASK_NAME) + "$1");
        s = REQUEST.matcher(s).replaceAll("$1 " + Matcher.quoteReplacement(MASK_LINE));
        return maskAddress(s);
    }

    // =================================================================
    // 내부
    // =================================================================

    /** 덤프 파일 구조: "=====" / "[시각] 트리거" / "Total Texts (N):" / "  [NN] 글자" / ">> Eval: ..." / "=====" */
    private static final class DumpState {
        boolean inTexts;
        // 덤프 파일: 모르는 줄도 글자 줄처럼 엄격하게. logcat: 덤프 밖 줄은 보통 로그라 공통 가림만.
        final boolean strictOutside;

        DumpState(boolean startInTexts, boolean strictOutside) {
            this.inTexts = startInTexts;
            this.strictOutside = strictOutside;
        }

        String redact(String line) {
            if (line.trim().isEmpty()) return line;
            if (line.startsWith("Total Texts (")) { inTexts = true; return line; }
            if (line.startsWith(">> Eval:")) { inTexts = false; return redactLogLine(line); }
            if (line.startsWith("=====")) { inTexts = false; return line; }
            Matcher m = DUMP_ITEM.matcher(line);
            if (m.matches()) return m.group(1) + redactScreenText(m.group(2));
            // 여러 줄 노드의 이어지는 줄
            if (inTexts) return redactScreenText(line);
            if (DUMP_HEADER.matcher(line).matches()) return redactLogLine(line);
            return strictOutside ? redactScreenText(line) : redactLogLine(line);
        }
    }

    /** 이벤트 로그, logcat, 덤프 헤더 한 줄: 공통 가림 + 클릭 글자는 덤프 글자 줄 규칙 */
    static String redactLogLine(String line) {
        if (line == null || line.isEmpty()) return line;
        Matcher m = CLICK_TEXT.matcher(line);
        if (m.find()) {
            return maskGeneric(line.substring(0, m.start(1)))
                    + redactScreenText(m.group(1))
                    + maskGeneric(line.substring(m.end(1)));
        }
        return maskGeneric(line);
    }

    static String maskAddress(String s) {
        List<int[]> spans = new ArrayList<>();
        for (Pattern p : ADDRESS) {
            Matcher m = p.matcher(s);
            while (m.find()) {
                if (m.end() <= m.start()) continue;
                int a = m.start();
                int b = m.end();
                while (a > 0 && isPhraseChar(s.charAt(a - 1))) a--;
                while (b < s.length() && isPhraseChar(s.charAt(b))) b++;
                // 앞뒤 공백은 남긴다
                while (a < b && s.charAt(a) == ' ') a++;
                while (b > a && s.charAt(b - 1) == ' ') b--;
                spans.add(new int[]{a, b});
            }
        }
        if (spans.isEmpty()) return s;
        spans.sort((x, y) -> Integer.compare(x[0], y[0]));
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        int curA = -1;
        int curB = -1;
        for (int[] sp : spans) {
            if (curA < 0) { curA = sp[0]; curB = sp[1]; continue; }
            if (sp[0] <= curB) { curB = Math.max(curB, sp[1]); continue; }
            sb.append(s, pos, curA).append(MASK_ADDR);
            pos = curB;
            curA = sp[0];
            curB = sp[1];
        }
        sb.append(s, pos, curA).append(MASK_ADDR);
        sb.append(s, curB, s.length());
        return sb.toString();
    }

    /** 주소 덩어리로 이어 붙일 글자: 한글, 영숫자, 공백, - ( ) . ~ * … */
    private static boolean isPhraseChar(char c) {
        return (c >= '가' && c <= '힣') || (c >= '0' && c <= '9')
                || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || c == ' ' || c == '-' || c == '(' || c == ')' || c == '.' || c == '~' || c == '*' || c == '…';
    }

    static String normalize(String s) {
        return s.replaceAll("\\s+", "");
    }

    private static int countDigits(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') n++;
        }
        return n;
    }

    private static Set<String> normalizedSet(String... words) {
        return new HashSet<>(Arrays.asList(normalizedArray(words)));
    }

    private static String[] normalizedArray(String... words) {
        String[] out = new String[words.length];
        for (int i = 0; i < words.length; i++) out[i] = normalize(words[i]);
        return out;
    }

    private static List<String> splitLines(String s) {
        List<String> out = new ArrayList<>(Arrays.asList(s.split("\n", -1)));
        // 끝의 "\n" 이 만드는 빈 꼬리는 버린다 (다시 붙일 때 trimTrailingNewlineLike 가 맞춘다)
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        for (int i = 0; i < out.size(); i++) {
            String l = out.get(i);
            if (l.endsWith("\r")) out.set(i, l.substring(0, l.length() - 1));
        }
        return out;
    }

    /** 원문이 줄바꿈으로 끝나지 않았으면 마지막 줄바꿈을 뗀다 */
    private static String trimTrailingNewlineLike(String original, StringBuilder sb) {
        if (!original.endsWith("\n") && sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }
}
