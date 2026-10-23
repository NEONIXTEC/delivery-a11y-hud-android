package com.ridermeter.a11y;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.TextSwitcher;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DecimalFormat;
import java.util.Locale;

/**
 * [FloatingPillService]
 * 화면 최상단 플로팅 알약(HUD) 오버레이 위젯 서비스.
 * TYPE_APPLICATION_OVERLAY를 사용하여 배민/쿠팡/내비 화면 위에 실시간 배달 건수, 목표, 수익을 롤링 표시합니다.
 *
 * ✨ 기능:
 * 1. 208dp 고정폭 미니멀 알약 디자인 (모든 이모지 배제, 모던 텍스트 HUD).
 * 2. 부드러운 드래그 앤 드롭 이동 및 가장자리 자동 흡착.
 * 3. 배달 완료 시 시안/골드 축하 팝업 연출.
 * 4. 롱프레스(꾹 누르기) 시 당일 목표 건수 설정 다이얼로그 표출.
 */
public class FloatingPillService extends Service {
    private static final String TAG = "FloatingPill";

    public static final String ACTION_START = "com.ridermeter.a11y.pill.START";
    public static final String ACTION_STOP = "com.ridermeter.a11y.pill.STOP";
    public static final String ACTION_DELIVERY_POPUP = "com.ridermeter.a11y.pill.DELIVERY_POPUP";
    public static final String ACTION_SYNC_POPUP = "com.ridermeter.a11y.pill.SYNC_POPUP";

    private WindowManager windowManager;
    private View pillView;
    private WindowManager.LayoutParams pillParams;
    private TextSwitcher textSwitcher;
    private View pillDot;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int currentPage = 0;
    private boolean isPausedForDelivery = false;

    private static final int PILL_WIDTH_DP = 208;
    private static int sTargetCount = 20;
    private static int sDeliveryCount = 0;
    private static long sTotalEarnings = 0L;

    private static final DecimalFormat MONEY_FMT = new DecimalFormat("#,###");

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (action == null) return START_NOT_STICKY;

        switch (action) {
            case ACTION_START:
                showFloatingPill();
                break;
            case ACTION_STOP:
                hideFloatingPill();
                stopSelf();
                break;
            case ACTION_DELIVERY_POPUP:
                int amount = intent.getIntExtra("amount", 0);
                String platform = intent.getStringExtra("platform");
                handleDeliveryPopup(amount, platform);
                break;
            case ACTION_SYNC_POPUP:
                int count = intent.getIntExtra("count", 0);
                long earnings = intent.getLongExtra("earnings", 0L);
                handleSyncPopup(count, earnings);
                break;
        }
        return START_STICKY;
    }

    public static void showDeliverySuccess(Context ctx, int amount, String platform) {
        Intent it = new Intent(ctx, FloatingPillService.class);
        it.setAction(ACTION_DELIVERY_POPUP);
        it.putExtra("amount", amount);
        it.putExtra("platform", platform);
        try {
            ctx.startService(it);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start FloatingPillService for delivery popup", e);
        }
    }

    public static void showDeliverySynced(Context ctx, int count, long earnings, String platform) {
        Intent it = new Intent(ctx, FloatingPillService.class);
        it.setAction(ACTION_SYNC_POPUP);
        it.putExtra("count", count);
        it.putExtra("earnings", earnings);
        it.putExtra("platform", platform);
        try {
            ctx.startService(it);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start FloatingPillService for sync popup", e);
        }
    }

    @SuppressLint({"InflateParams", "ClickableViewAccessibility"})
    private void showFloatingPill() {
        if (pillView != null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission not granted");
            return;
        }

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int pillWidthPx = (int) (PILL_WIDTH_DP * dm.density + 0.5f);

        int layoutType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        pillParams = new WindowManager.LayoutParams(
                pillWidthPx,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );

        pillParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        pillParams.y = (int) (64 * dm.density);

        LayoutInflater inflater = LayoutInflater.from(this);
        pillView = inflater.inflate(R.layout.floating_pill_widget, null);

        textSwitcher = pillView.findViewById(R.id.pill_text_switcher);
        pillDot = pillView.findViewById(R.id.pill_dot);

        textSwitcher.setFactory(() -> {
            TextView tv = new TextView(this);
            tv.setTextSize(12);
            tv.setTextColor(Color.WHITE);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            return tv;
        });

        textSwitcher.setInAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_in));
        textSwitcher.setOutAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_out));

        setupPillTouchListener();
        windowManager.addView(pillView, pillParams);

        startRollingRunnable();
    }

    private void hideFloatingPill() {
        handler.removeCallbacksAndMessages(null);
        if (pillView != null && windowManager != null) {
            try {
                windowManager.removeView(pillView);
            } catch (Exception ignored) {}
            pillView = null;
        }
    }

    private void startRollingRunnable() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!isPausedForDelivery && pillView != null) {
                    showNextPage();
                }
                handler.postDelayed(this, 3500);
            }
        });
    }

    private void showNextPage() {
        if (textSwitcher == null) return;
        String text;
        if (currentPage == 0) {
            text = String.format(Locale.KOREA, "목표 %d건  완료 %d건", sTargetCount, sDeliveryCount);
            currentPage = 1;
        } else {
            text = String.format(Locale.KOREA, "총 수입 %s원", MONEY_FMT.format(sTotalEarnings));
            currentPage = 0;
        }
        textSwitcher.setText(text);
    }

    private void handleDeliveryPopup(int amount, String platform) {
        sDeliveryCount++;
        sTotalEarnings += amount;

        isPausedForDelivery = true;
        if (pillView != null) {
            pillView.setBackgroundResource(R.drawable.bg_floating_pill_success);
        }
        if (textSwitcher != null) {
            textSwitcher.setText(String.format(Locale.KOREA, "+%s원 완료!", MONEY_FMT.format(amount)));
        }

        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(120);
        } catch (Exception ignored) {}

        handler.postDelayed(() -> {
            isPausedForDelivery = false;
            if (pillView != null) {
                pillView.setBackgroundResource(R.drawable.bg_floating_pill);
            }
            showNextPage();
        }, 4000);
    }

    private void handleSyncPopup(int count, long earnings) {
        sDeliveryCount = count;
        sTotalEarnings = earnings;
        if (textSwitcher != null) {
            textSwitcher.setText(String.format(Locale.KOREA, "실적 동기화: %d건", count));
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupPillTouchListener() {
        if (pillView == null) return;

        pillView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private long touchDownTime;
            private boolean isLongClickTriggered = false;

            private final Runnable longPressRunnable = () -> {
                isLongClickTriggered = true;
                showSetGoalDialog();
            };

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = pillParams.x;
                        initialY = pillParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        touchDownTime = System.currentTimeMillis();
                        isLongClickTriggered = false;
                        handler.postDelayed(longPressRunnable, 600);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (Math.abs(dx) > 15 || Math.abs(dy) > 15) {
                            handler.removeCallbacks(longPressRunnable);
                        }
                        pillParams.x = initialX + (int) dx;
                        pillParams.y = initialY + (int) dy;
                        if (pillView != null && windowManager != null) {
                            windowManager.updateViewLayout(pillView, pillParams);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        handler.removeCallbacks(longPressRunnable);
                        if (!isLongClickTriggered && (System.currentTimeMillis() - touchDownTime < 300)) {
                            // 단순 클릭 시 다음 페이지 전환
                            showNextPage();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void showSetGoalDialog() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int dialogWidthPx = (int) (320 * dm.density + 0.5f);

        int layoutType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams dlgParams = new WindowManager.LayoutParams(
                dialogWidthPx,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
        );
        dlgParams.gravity = Gravity.CENTER;

        ContextThemeWrapper ctw = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        View dlgView = LayoutInflater.from(ctw).inflate(R.layout.dialog_set_goal, null);

        TextView tvVal = dlgView.findViewById(R.id.tv_goal_value);
        if (tvVal != null) tvVal.setText(String.valueOf(sTargetCount));

        View btnMinus = dlgView.findViewById(R.id.btn_goal_minus);
        View btnPlus = dlgView.findViewById(R.id.btn_goal_plus);
        if (btnMinus != null && tvVal != null) {
            btnMinus.setOnClickListener(v -> {
                if (sTargetCount > 5) sTargetCount -= 5;
                tvVal.setText(String.valueOf(sTargetCount));
            });
        }
        if (btnPlus != null && tvVal != null) {
            btnPlus.setOnClickListener(v -> {
                sTargetCount += 5;
                tvVal.setText(String.valueOf(sTargetCount));
            });
        }

        View btnOk = dlgView.findViewById(R.id.btn_goal_save);
        if (btnOk != null) {
            btnOk.setOnClickListener(v -> {
                try {
                    windowManager.removeView(dlgView);
                } catch (Exception ignored) {}
                Toast.makeText(this, "목표 " + sTargetCount + "건 설정 완료", Toast.LENGTH_SHORT).show();
            });
        }

        View btnClose = dlgView.findViewById(R.id.btn_goal_cancel);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> {
                try {
                    windowManager.removeView(dlgView);
                } catch (Exception ignored) {}
            });
        }

        windowManager.addView(dlgView, dlgParams);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        hideFloatingPill();
    }
}
