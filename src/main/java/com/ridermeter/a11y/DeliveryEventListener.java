package com.ridermeter.a11y;

/**
 * 배달 플랫폼(배민/쿠팡) 접근성 화면 인식 이벤트 콜백 인터페이스.
 * 터치나 매크로가 일절 없는 순수 Read-Only 수신 리스너.
 * 엔진은 이 콜백으로 값만 넘기고, 저장이나 전송은 하지 않는다 (호스트 앱이 정한다).
 */
public interface DeliveryEventListener {
    /**
     * 배달 완료 모달이 감지되었을 때 호출됩니다.
     * (배민이 완료 모달을 띄우지 않고 "이미 전달완료된 배달입니다."로 끝난 경우의 누락 복구도 여기로 온다)
     * @param platform "baemin" 또는 "coupang"
     * @param amount 배달료 (원). 배민은 완료가 확실한데 금액을 못 읽으면 3,500원, 쿠팡은 못 읽으면 0원
     * @param orderId 주문번호. 지금 엔진은 주문번호를 밖으로 넘기지 않으므로 항상 null
     */
    void onDeliveryCompleted(String platform, int amount, String orderId);

    /**
     * 마이페이지/내수입 화면에서 일일 누적 실적이 동기화되었을 때 호출됩니다.
     * @param platform "baemin" 또는 "coupang"
     * @param totalCount 당일 총 배달 완료 건수
     * @param totalEarnings 당일 총 배달 수입 (원). 쿠팡은 일별 배달 수수료 + 같은 날짜의 미션 보너스
     */
    void onEarningsSynced(String platform, int totalCount, long totalEarnings);

    /**
     * 미션 달성이 확인되었을 때 호출됩니다.
     * 쿠팡: 배달완료 모달의 깜짝미션 "성공! 1만 5천원 보너스" (count 는 "3건 배달"의 3, 모르면 0. timeSlot 은 null)
     * 배민: 미션 내역 화면에서 배민이 완료로 보여준 줄 (영업일마다 같은 미션은 한 번만)
     */
    void onMissionCompleted(String platform, int count, long bonus, String timeSlot);

    /**
     * 배민 배달미션 줄을 읽을 때마다 호출됩니다 (진행 숫자 반영, 예정 미션 등록용).
     * 진행 건수 계산이나 보너스 합산 같은 미션 장부는 호스트 앱이 맡는다.
     * @param platform   "baemin"
     * @param current    진행 건수 ("3, /13" 의 3). 모르면 0, 완료 줄이면 target 과 같다
     * @param target     목표 건수
     * @param bonus      보너스 (원)
     * @param timeSlot   "오늘 17:30 ~ 20:30"
     * @param inProgress 진행 숫자를 읽은 진행 중 미션이거나 완료 줄이면 true, 예정 미션이나 홈 시트의 숫자 없는 카드면 false
     * @param completed  배민이 완료로 보여준 줄이면 true
     */
    default void onMissionObserved(String platform, int current, int target, long bonus, String timeSlot,
                                   boolean inProgress, boolean completed) {}
}
