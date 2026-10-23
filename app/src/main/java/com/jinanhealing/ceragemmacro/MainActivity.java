package com.jinanhealing.ceragemmacro;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    public static final String EXTRA_SCROLL_BOTTOM = "scroll_bottom";
    public static volatile String lastReport = null;
    public static volatile String lastDump = null;
    public static volatile String lastIssueReport = null;
    /** 점검이 끝나면 서비스가 채운다: {제목, 내용}. 화면이 보이면 팝업으로 보여 주고 비운다. */
    public static volatile String[] pendingCheck = null;

    private static final String ACT_ON = "com.jinanhealing.ceragemmacro.ON";
    private static final String ACT_RUN = "com.jinanhealing.ceragemmacro.RUN";
    private static final String ACT_OFF = "com.jinanhealing.ceragemmacro.OFF";

    private static final int RED = Color.parseColor("#C8102E");
    private static final int INK = Color.parseColor("#222222");
    private static final int MUTED = Color.parseColor("#666666");

    // 기기 칸(갈색)
    private static final int NUM_OFF = Color.parseColor("#E6DDD4");
    private static final int NUM_ON = Color.parseColor("#F6D58E");
    private static final int NUM_RUN = Color.parseColor("#9ED6B4");
    private static final int SEG_ON = Color.parseColor("#E0C3A0");
    private static final int SEG_OFF = Color.parseColor("#F4EEE8");
    private static final int SEG_ON_TEXT = Color.parseColor("#3A2316");
    private static final int SEG_OFF_TEXT = Color.parseColor("#7A6B60");
    private static final int MOD_DOT = Color.parseColor("#5A32A8");

    private static final String[] SPEED_NAMES = {"저속", "정속", "고속"};
    private static final int[] SPEED_COLORS = {Color.parseColor("#4E7CA8"), Color.parseColor("#5C6670"), Color.parseColor("#6A4C93")};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private int tick = 0;
    private boolean ticking = false;

    private ScrollView scroll;
    private TextView statusView;
    private Button a11yButton;
    private EditText countEdit, prefixEdit, playIdEdit, changeEdit, changeNoEdit, changeSpeedEdit;
    private TextView reportView;
    private Button shareDumpButton, shareIssueButton;
    private TextView histInfo;
    private Config cfg;

    // 기기 화면 부품
    private LinearLayout leftCol, rightCol;
    private final List<Bed> beds = new ArrayList<>();
    private TextView elapsedView, runStateView, countsView, lastLineView, modeChangeText;
    private View runDot, modeChangeBox;
    private LinearLayout speedDots;
    private Button applyButton, checkButton;

    /** 기기 하나의 화면 부품 */
    private static class Bed {
        int dev;
        boolean left;
        LinearLayout ring;
        LinearLayout row;
        TextView spine, abd, num;
        View modDot;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = Config.load(this);
        buildUi();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        ticking = true;
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        ticking = false;
        ui.removeCallbacks(ticker);
        super.onPause();
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!ticking) return;
            tick++;
            MacroService svc = MacroService.instance;
            // 서비스가 바꾸는 상태(켜짐·실행·점검 결과)를 가끔 다시 읽는다
            boolean working = svc != null && svc.isRunning();
            if (working ? tick % 2 == 0 : tick % 3 == 0) syncFromPrefs();
            updateElapsed();
            showPendingCheck();
            ui.postDelayed(this, 1000);
        }
    };

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (ACT_ON.equals(a)) startMode(MacroService.MODE_ON, "");
        else if (ACT_RUN.equals(a)) startMode(MacroService.MODE_RUN, "");
        else if (ACT_OFF.equals(a)) startMode(MacroService.MODE_OFF, "");
        // 같은 바로가기 인텐트가 화면 회전 등으로 다시 실행되지 않게 지운다
        intent.setAction(Intent.ACTION_MAIN);
        refresh();
        if (intent.getBooleanExtra(EXTRA_SCROLL_BOTTOM, false) && scroll != null) {
            intent.removeExtra(EXTRA_SCROLL_BOTTOM);
            // 끝나면 기기 칸과 점검 결과가 보이도록 맨 위로
            scroll.post(() -> scroll.smoothScrollTo(0, 0));
        }
    }

    /** 서비스에 일을 시킨다. 시작했으면 true. */
    private boolean startMode(int mode, String speedLabel) {
        if (countEdit != null) saveFromUi(false);
        MacroService svc = MacroService.instance;
        if (svc == null) {
            Toast.makeText(this, "먼저 접근성에서 '세라젬 일괄조작'을 켜 주세요", Toast.LENGTH_LONG).show();
            openA11ySettings();
            return false;
        }
        if (svc.isRunning()) {
            Toast.makeText(this, "이미 실행 중이에요 (볼륨 아래 두 번 = 중지)", Toast.LENGTH_SHORT).show();
            return false;
        }
        syncFromPrefs();
        if (mode == MacroService.MODE_APPLY) {
            if (cfg.modifiedCount() == 0) {
                Toast.makeText(this, "수정한 기기가 없어요", Toast.LENGTH_SHORT).show();
                return false;
            }
        } else if (mode == MacroService.MODE_CHANGE) {
            if (cfg.selSpine.isEmpty()) {
                Toast.makeText(this, "모드를 바꿀 기기의 척추를 하나 이상 선택해 주세요", Toast.LENGTH_SHORT).show();
                return false;
            }
        } else if (mode != MacroService.MODE_CHECK) {
            if (cfg.selectedList().isEmpty()) {
                Toast.makeText(this, "대상 기기의 척추나 복부를 하나 이상 선택해 주세요", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
        // 경과 시간: 실행을 누른 때부터, 끄면 멈춤
        if (mode == MacroService.MODE_RUN || mode == MacroService.MODE_ON_RUN) {
            long now = System.currentTimeMillis();
            Config.setRunStarted(this, now);
            cfg.runStartedAt = now;
        } else if (mode == MacroService.MODE_OFF) {
            Config.setRunStarted(this, 0);
            cfg.runStartedAt = 0;
        }
        if (mode == MacroService.MODE_CHECK) {
            pendingCheck = null;
            Config.setDiffDevs(this, new ArrayList<Integer>());
            cfg.diffDevs.clear();
        }
        boolean started = svc.start(mode, speedLabel);
        updateAll();
        return started;
    }

    /**
     * 모드 변경: 누를 때마다 저속 → 정속 → 고속 → 저속… 골라 두기만 한다.
     * 기기에는 '실행'을 눌렀을 때, 그 기기의 지금 속도와 다른 기기에만 적용된다.
     */
    private void cycleSpeed() {
        syncFromPrefs();
        int next = (cfg.lastSpeedIdx + 1) % 3;
        Config.setLastSpeed(this, next);
        cfg.lastSpeedIdx = next;
        updateAll();
        Toast.makeText(this, SPEED_NAMES[next] + "으로 골랐어요 — 실행을 누르면 속도가 다른 기기만 바꿔요", Toast.LENGTH_SHORT).show();
    }

    /** 수정 사항 적용: 어떤 단계(끄기·켜기·실행)를 할지 고른 뒤 시작한다. */
    private void showApplyDialog() {
        syncFromPrefs();
        if (cfg.modifiedCount() == 0) {
            Toast.makeText(this, "수정한 기기가 없어요", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= cfg.count; i++) {
            if (cfg.modified(i)) sb.append(String.format(Locale.US, "%02d번  %s\n", i, cfg.planText(i)));
        }
        TextView plan = text(sb.toString().trim(), 14, INK, false);
        plan.setPadding(0, 0, 0, dp(10));
        box.addView(plan);
        box.addView(text("할 단계를 고르세요", 13, MUTED, true));
        final CheckBox cOff = stepCheck("끄기  (해제한 부위를 멈추거나 전원 끄기)");
        final CheckBox cOn = stepCheck("켜기  (꺼진 기기 전원 켜기)");
        final CheckBox cRun = stepCheck("실행  (새로 고른 부위 작동)");
        box.addView(cOff);
        box.addView(cOn);
        box.addView(cRun);
        new android.app.AlertDialog.Builder(this)
                .setTitle("수정 사항 적용")
                .setView(box)
                .setPositiveButton("적용", (d, w) -> {
                    MacroService.applyOff = cOff.isChecked();
                    MacroService.applyOn = cOn.isChecked();
                    MacroService.applyRun = cRun.isChecked();
                    if (!(cOff.isChecked() || cOn.isChecked() || cRun.isChecked())) {
                        Toast.makeText(this, "단계를 하나 이상 골라 주세요", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    startMode(MacroService.MODE_APPLY, "");
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private CheckBox stepCheck(String label) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextSize(15);
        cb.setChecked(true);
        return cb;
    }

    private void openA11ySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- UI

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#FAF7F5"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(32));
        scroll.addView(root);

        TextView title = text("세라젬 일괄조작", 24, INK, true);
        root.addView(title);
        TextView sub = text("선택한 칸만 세라젬 IoT 앱에서 차례로 대신 눌러 줍니다. 실행 중에는 폰을 만지지 마세요. 볼륨 아래 버튼을 두 번 누르면 멈춥니다.", 13, MUTED, false);
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        // 끄기 · 켜기 · 실행
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(topButton("■", "끄기", Color.parseColor("#444444"), v -> startMode(MacroService.MODE_OFF, "")));
        row1.addView(topButton("⚡", "켜기", RED, v -> startMode(MacroService.MODE_ON, "")));
        row1.addView(topButton("▶", "실행", Color.parseColor("#1F6F50"), v -> startMode(MacroService.MODE_RUN, "")));
        root.addView(row1);

        // 전체 선택 · 전체 해제 · 모드 변경
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(lightButton("전체 선택", v -> setAll(true)));
        row2.addView(lightButton("전체 해제", v -> setAll(false)));
        row2.addView(modeChangeButton());
        root.addView(row2);

        // 대상 기기 + 범례
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(14), 0, dp(6));
        head.addView(text("대상 기기", 15, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(legend(NUM_OFF, "꺼짐", false));
        head.addView(legend(NUM_ON, "켜짐", false));
        head.addView(legend(NUM_RUN, "실행", false));
        head.addView(legend(MOD_DOT, "수정", true));
        root.addView(head);

        // 기기 칸
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setPadding(dp(10), dp(10), dp(10), dp(5));
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(Color.WHITE);
        pbg.setCornerRadius(dp(18));
        pbg.setStroke(dp(1), Color.parseColor("#E4D8CC"));
        panel.setBackground(pbg);
        leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        panel.addView(leftCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(corridor(), new LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.MATCH_PARENT));
        panel.addView(rightCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 점검 · 수정 사항 적용
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        checkButton = new Button(this);
        checkButton.setAllCaps(false);
        checkButton.setText("🔍 점검");
        checkButton.setTextSize(15);
        checkButton.setTypeface(Typeface.DEFAULT_BOLD);
        checkButton.setTextColor(INK);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(Color.WHITE);
        cbg.setCornerRadius(dp(16));
        cbg.setStroke(dp(2), INK);
        checkButton.setBackground(cbg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        clp.setMargins(dp(4), dp(10), dp(4), 0);
        checkButton.setLayoutParams(clp);
        checkButton.setOnClickListener(v -> startMode(MacroService.MODE_CHECK, ""));
        row3.addView(checkButton);
        applyButton = new Button(this);
        applyButton.setAllCaps(false);
        applyButton.setTextSize(15);
        applyButton.setTypeface(Typeface.DEFAULT_BOLD);
        applyButton.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        alp.setMargins(dp(4), dp(10), dp(4), 0);
        applyButton.setLayoutParams(alp);
        applyButton.setOnClickListener(v -> showApplyDialog());
        row3.addView(applyButton);
        root.addView(row3);

        // 실행 후 경과 시간 (버튼 한 줄 아래)
        LinearLayout timeCard = card(root);
        ((LinearLayout.LayoutParams) timeCard.getLayoutParams()).topMargin = dp(12);
        timeCard.setOrientation(LinearLayout.HORIZONTAL);
        timeCard.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tl = new LinearLayout(this);
        tl.setOrientation(LinearLayout.VERTICAL);
        tl.addView(text("실행 후 경과", 12, MUTED, true));
        elapsedView = text("00:00", 32, INK, true);
        tl.addView(elapsedView);
        timeCard.addView(tl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout tr = new LinearLayout(this);
        tr.setOrientation(LinearLayout.VERTICAL);
        tr.setGravity(Gravity.END);
        LinearLayout st = new LinearLayout(this);
        st.setOrientation(LinearLayout.HORIZONTAL);
        st.setGravity(Gravity.CENTER_VERTICAL);
        runDot = new View(this);
        runDot.setLayoutParams(new LinearLayout.LayoutParams(dp(10), dp(10)));
        st.addView(runDot);
        runStateView = text("", 13, INK, true);
        runStateView.setPadding(dp(6), 0, 0, 0);
        st.addView(runStateView);
        tr.addView(st);
        countsView = text("", 12, MUTED, true);
        countsView.setGravity(Gravity.END);
        countsView.setPadding(0, dp(4), 0, 0);
        tr.addView(countsView);
        timeCard.addView(tr);
        lastLineView = text("", 12, MUTED, false);
        lastLineView.setPadding(dp(6), 0, 0, 0);
        root.addView(lastLineView);

        // ---- 아래쪽은 그대로 (최근 결과 · 속도 · 설정 · 접근성 · 실패 기록 · 화면 분석)

        // 결과
        root.addView(section("최근 결과"));
        LinearLayout reportCard = card(root);
        reportView = text("", 14, INK, false);
        reportView.setTextIsSelectable(true);
        reportCard.addView(reportView);
        shareIssueButton = smallButton("진행 기록 공유");
        shareIssueButton.setOnClickListener(v -> shareText(lastIssueReport, "세라젬 일괄조작 진행 기록"));
        reportCard.addView(shareIssueButton);

        // 속도
        root.addView(section("속도"));
        LinearLayout speedCard = card(root);
        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        String[] labels = {"안정", "보통", "빠름"};
        for (int i = 0; i < 3; i++) {
            final int sp = i;
            RadioButton rb = new RadioButton(this);
            rb.setText(labels[i]);
            rb.setTextSize(16);
            rb.setId(View.generateViewId());
            rb.setChecked(cfg.speed == i);
            rb.setOnClickListener(v -> {
                cfg.speed = sp;
                cfg.save(this);
            });
            RadioGroup.LayoutParams rlp = new RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            rb.setLayoutParams(rlp);
            rg.addView(rb);
        }
        speedCard.addView(rg);
        speedCard.addView(text("빠름에서 일부 기기가 안 켜지면 보통이나 안정으로 바꿔 보세요.", 13, MUTED, false));

        // 설정
        root.addView(section("설정"));
        LinearLayout setCard = card(root);
        countEdit = field(setCard, "기기 수", String.valueOf(cfg.count), true);
        prefixEdit = field(setCard, "기기 이름 앞부분 (뒤에 01, 02… 자동)", cfg.prefix, false);
        changeEdit = field(setCard, "모드 변경 버튼으로 바꿀 모드", cfg.changeModeName, false);
        changeNoEdit = field(setCard, "그 모드의 번호 (모드 창에서 몇 번인지, 릴렉스 = 2)", cfg.changeModeNo, true);
        changeSpeedEdit = field(setCard, "예전 방식 모드 변경 후 속도 (모드 변경 버튼은 누를 때마다 저속→정속→고속으로 보내요)", cfg.changeSpeed, false);
        playIdEdit = field(setCard, "▶ 버튼 ID (비워 두면 자동으로 찾음)", cfg.playId, false);
        Button save = smallButton("설정 저장");
        save.setOnClickListener(v -> saveFromUi(true));
        setCard.addView(save);

        // 접근성 상태
        LinearLayout statusRow = card(root);
        statusView = text("", 15, INK, true);
        statusRow.addView(statusView);
        a11yButton = smallButton("접근성 설정 열기");
        a11yButton.setOnClickListener(v -> openA11ySettings());
        statusRow.addView(a11yButton);

        // 실패 기록 모음
        root.addView(section("실패 기록 모음"));
        LinearLayout histCard = card(root);
        histInfo = text("", 14, INK, false);
        histCard.addView(histInfo);
        histCard.addView(text("실패·확인 안 됨·앱 재실행·네트워크 알림이 있었던 실행만 자동으로 쌓여요. 공유하면 맨 위에 기기별·원인별 횟수가 정리돼요.", 13, MUTED, false));
        LinearLayout histRow = new LinearLayout(this);
        histRow.setOrientation(LinearLayout.HORIZONTAL);
        Button histShare = smallButton("실패 기록 공유");
        histShare.setOnClickListener(v -> {
            String h = FailHistory.withSummary(this);
            if (h.isEmpty()) Toast.makeText(this, "아직 쌓인 실패 기록이 없어요", Toast.LENGTH_SHORT).show();
            else shareText(h, "세라젬 일괄조작 실패 기록 모음");
        });
        Button histClear = smallButton("기록 지우기");
        histClear.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setMessage("실패 기록을 모두 지울까요?")
                .setPositiveButton("지우기", (d, w) -> {
                    FailHistory.clear(this);
                    refresh();
                })
                .setNegativeButton("취소", null)
                .show());
        histRow.addView(histShare);
        histRow.addView(histClear);
        histCard.addView(histRow);

        // 화면 분석
        root.addView(section("화면 분석 (문제 생겼을 때)"));
        LinearLayout dumpCard = card(root);
        dumpCard.addView(text("버튼을 누르고 10초 안에 세라젬 앱의 확인하고 싶은 화면(목록 또는 기기 화면)을 띄워 두세요. 화면 구조가 Download 폴더에 저장되고, 아래 공유 버튼으로 보낼 수 있어요.", 13, MUTED, false));
        Button dump = smallButton("10초 뒤 화면 분석");
        dump.setOnClickListener(v -> {
            MacroService svc = MacroService.instance;
            if (svc == null) {
                Toast.makeText(this, "먼저 접근성 권한을 켜 주세요", Toast.LENGTH_LONG).show();
                openA11ySettings();
                return;
            }
            svc.dumpLater(10);
        });
        dumpCard.addView(dump);
        shareDumpButton = smallButton("분석 결과 공유");
        shareDumpButton.setOnClickListener(v -> shareDump());
        dumpCard.addView(shareDumpButton);

        setContentView(scroll);
        rebuildBeds();
    }

    // ---------------------------------------------------------------- 갱신

    private void refresh() {
        if (statusView == null) return;
        syncFromPrefs();
        boolean on = MacroService.instance != null;
        statusView.setText(on ? "● 접근성 권한 켜짐 — 사용 준비 완료" : "● 접근성 권한이 꺼져 있어요");
        statusView.setTextColor(on ? Color.parseColor("#1F6F50") : RED);
        a11yButton.setVisibility(on ? View.GONE : View.VISIBLE);
        String r = lastReport;
        reportView.setText(r == null ? "아직 실행 기록이 없어요." : r);
        shareDumpButton.setVisibility(lastDump == null ? View.GONE : View.VISIBLE);
        shareIssueButton.setVisibility(lastIssueReport == null ? View.GONE : View.VISIBLE);
        int hc = FailHistory.count(this);
        histInfo.setText(hc == 0 ? "쌓인 실패 기록 없음" : "쌓인 실패 기록 " + hc + "회");
        showPendingCheck();
    }

    /** 서비스가 저장해 둔 상태·기준 선택·점검 결과를 화면용 설정에 다시 읽어 온다. (선택은 건드리지 않음) */
    private void syncFromPrefs() {
        Config f = Config.load(this);
        cfg.status = f.status;
        cfg.baseSpine = f.baseSpine;
        cfg.baseAbd = f.baseAbd;
        cfg.diffDevs = f.diffDevs;
        cfg.runStartedAt = f.runStartedAt;
        cfg.lastSpeedIdx = f.lastSpeedIdx;
        updateAll();
    }

    private void updateAll() {
        if (elapsedView == null) return;
        for (Bed b : beds) updateBed(b);
        updateElapsed();
        updateButtons();
    }

    private void updateBed(Bed b) {
        boolean sp = cfg.selSpine.contains(b.dev), ab = cfg.selAbd.contains(b.dev);
        int st = cfg.statusOf(b.dev);
        int numBg = NUM_OFF;
        if ((st & (Config.ST_SPINE | Config.ST_ABD)) != 0) numBg = NUM_RUN;      // 작동 중
        else if ((st & Config.ST_POWER) != 0) numBg = NUM_ON;                     // 전원만 켜짐
        setBg(b.num, numBg);
        b.spine.setText(segText("척추", sp, b.left));
        b.abd.setText(segText("복부", ab, b.left));
        setBg(b.spine, sp ? SEG_ON : SEG_OFF);
        setBg(b.abd, ab ? SEG_ON : SEG_OFF);
        b.spine.setTextColor(sp ? SEG_ON_TEXT : SEG_OFF_TEXT);
        b.abd.setTextColor(ab ? SEG_ON_TEXT : SEG_OFF_TEXT);
        boolean mod = cfg.modified(b.dev);
        b.modDot.setVisibility(mod ? View.VISIBLE : View.GONE);
        boolean diff = cfg.diffDevs.contains(b.dev);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(17));
        ring.setColor(diff ? RED : Color.TRANSPARENT);
        b.ring.setBackground(ring);
        String state = (st & (Config.ST_SPINE | Config.ST_ABD)) != 0 ? "실행 중" : (st & Config.ST_POWER) != 0 ? "켜짐" : "꺼짐";
        b.row.setContentDescription("세라젬 " + b.dev + "번, " + state + ", 척추 " + (sp ? "선택" : "해제")
                + ", 복부 " + (ab ? "선택" : "해제") + (mod ? ", 수정됨" : "") + (diff ? ", 세라젬 앱과 다름" : ""));
    }

    /** 체크는 같은 글자 줄 안에: 왼쪽 칸은 글자 앞(✓ 복부), 오른쪽 칸은 글자 뒤(척추 ✓) */
    private static String segText(String label, boolean sel, boolean leftColumn) {
        if (!sel) return label;
        return leftColumn ? "✓ " + label : label + " ✓";
    }

    private void updateElapsed() {
        if (elapsedView == null) return;
        long start = cfg.runStartedAt;
        long total = start > 0 ? Math.max(0, (System.currentTimeMillis() - start) / 1000) : 0;
        long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        elapsedView.setText(h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s) : String.format(Locale.US, "%02d:%02d", m, s));
        boolean anyRun = false, anyOn = false;
        for (int i = 1; i <= cfg.count; i++) {
            int st = cfg.statusOf(i);
            if ((st & (Config.ST_SPINE | Config.ST_ABD)) != 0) anyRun = true;
            if ((st & Config.ST_POWER) != 0) anyOn = true;
        }
        MacroService svc = MacroService.instance;
        boolean working = svc != null && svc.isRunning();
        String stText;
        int dot;
        if (working) {
            stText = "조작 중";
            dot = Color.parseColor("#C98A00");
        } else if (anyRun) {
            stText = "실행 중";
            dot = Color.parseColor("#1F6F50");
        } else if (anyOn) {
            stText = "켜짐 · 대기";
            dot = Color.parseColor("#C98A00");
        } else {
            stText = "꺼짐";
            dot = Color.parseColor("#8B86A0");
        }
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(dot);
        runDot.setBackground(d);
        runStateView.setText(stText);
        countsView.setText("척추 " + cfg.selSpine.size() + "/" + cfg.count + " · 복부 " + cfg.selAbd.size() + "/" + cfg.count);
        String rep = lastReport;
        String line = "";
        if (rep != null) {
            String[] ls = rep.split("\n");
            if (ls.length >= 2) line = ls[0].trim() + "  " + ls[1].trim();
        }
        lastLineView.setText(line);
        lastLineView.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void updateButtons() {
        if (applyButton == null) return;
        int n = cfg.modifiedCount();
        applyButton.setText("수정 사항 적용\n" + (n == 0 ? "수정한 기기 없음" : n + "대 수정됨"));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(n == 0 ? Color.parseColor("#CFC7C2") : Color.parseColor("#3A2316"));
        applyButton.setBackground(g);
        applyButton.setTextColor(n == 0 ? Color.parseColor("#6E655F") : Color.WHITE);
        if (modeChangeText != null) {
            int li = cfg.lastSpeedIdx;
            modeChangeText.setText("모드 변경 · " + (li < 0 || li > 2 ? "안 함" : SPEED_NAMES[li]));
            GradientDrawable mg = new GradientDrawable();
            mg.setCornerRadius(dp(16));
            mg.setColor(li < 0 || li > 2 ? Color.parseColor("#5C6670") : SPEED_COLORS[li]);
            modeChangeBox.setBackground(mg);
            for (int i = 0; i < speedDots.getChildCount(); i++) {
                GradientDrawable dg = new GradientDrawable();
                dg.setCornerRadius(dp(3));
                dg.setColor(i <= li ? Color.WHITE : Color.argb(100, 255, 255, 255));
                speedDots.getChildAt(i).setBackground(dg);
            }
        }
    }

    private void showPendingCheck() {
        final String[] p = pendingCheck;
        if (p == null || isFinishing()) return;
        pendingCheck = null;
        boolean same = p[0].startsWith("동일");
        new android.app.AlertDialog.Builder(this)
                .setTitle(same ? "세라젬 앱과 동일해요" : "세라젬 앱과 달라요")
                .setMessage(p[1])
                .setPositiveButton("확인", null)
                .show();
        syncFromPrefs();
    }

    // ---------------------------------------------------------------- 기기 칸

    private void rebuildBeds() {
        leftCol.removeAllViews();
        rightCol.removeAllViews();
        beds.clear();
        int half = (cfg.count + 1) / 2;
        for (int i = 1; i <= cfg.count; i++) {
            boolean left = i <= half;
            Bed b = makeBed(i, left);
            beds.add(b);
            (left ? leftCol : rightCol).addView(b.ring);
        }
        updateAll();
    }

    private Bed makeBed(final int dev, boolean left) {
        final Bed b = new Bed();
        b.dev = dev;
        b.left = left;
        b.ring = new LinearLayout(this);
        b.ring.setPadding(dp(3), dp(3), dp(3), dp(3));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66));
        rlp.bottomMargin = dp(5);
        b.ring.setLayoutParams(rlp);

        b.row = new LinearLayout(this);
        b.row.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable rbg = new GradientDrawable();
        rbg.setColor(Color.WHITE);
        rbg.setCornerRadius(dp(14));
        b.row.setBackground(rbg);
        b.row.setClipToOutline(true);
        b.row.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        b.ring.addView(b.row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        b.spine = segment();
        b.abd = segment();
        b.spine.setOnClickListener(v -> toggle(b, true));
        b.abd.setOnClickListener(v -> toggle(b, false));

        // 숫자 칸: 머리(복도) 쪽 얇은 칸. 점은 수정 표시.
        FrameLayout numWrap = new FrameLayout(this);
        b.num = new TextView(this);
        b.num.setText(String.format(Locale.US, "%02d", dev));
        b.num.setTextSize(13);
        b.num.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        b.num.setTextColor(Color.parseColor("#2B1D16"));
        b.num.setGravity(Gravity.CENTER);
        numWrap.addView(b.num, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        b.modDot = new View(this);
        GradientDrawable dd = new GradientDrawable();
        dd.setShape(GradientDrawable.OVAL);
        dd.setColor(MOD_DOT);
        b.modDot.setBackground(dd);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(dp(7), dp(7));
        mlp.gravity = Gravity.TOP | (left ? Gravity.END : Gravity.START);
        mlp.setMargins(dp(3), dp(4), dp(3), 0);
        numWrap.addView(b.modDot, mlp);

        LinearLayout.LayoutParams seg1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        LinearLayout.LayoutParams seg2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        LinearLayout.LayoutParams numLp = new LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.MATCH_PARENT);
        seg1.rightMargin = dp(2);
        seg2.rightMargin = dp(2);
        if (left) {
            // 복도 쪽(오른쪽)이 머리: 복부 · 척추 · 숫자
            b.row.addView(b.abd, seg1);
            b.row.addView(b.spine, seg2);
            b.row.addView(numWrap, numLp);
        } else {
            b.row.addView(numWrap, numLp);
            b.row.addView(b.spine, seg1);
            b.row.addView(b.abd, seg2);
        }
        return b;
    }

    private TextView segment() {
        TextView t = new TextView(this);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        t.setClickable(true);
        return t;
    }

    private void setBg(View v, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        v.setBackground(g);
    }

    private void toggle(Bed b, boolean spine) {
        if (spine) {
            if (!cfg.selSpine.remove(b.dev)) cfg.selSpine.add(b.dev);
        } else {
            if (!cfg.selAbd.remove(b.dev)) cfg.selAbd.add(b.dev);
        }
        cfg.diffDevs.remove(b.dev);       // 만지면 점검 표시(빨간 테두리)는 지운다
        cfg.save(this);                   // 선택 + 점검 표시 저장
        updateAll();
    }

    private void setAll(boolean on) {
        cfg.selSpine.clear();
        cfg.selAbd.clear();
        if (on) {
            for (int i = 1; i <= cfg.count; i++) {
                cfg.selSpine.add(i);
                cfg.selAbd.add(i);
            }
        }
        cfg.save(this);
        updateAll();
    }

    private View corridor() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        View l1 = new View(this);
        l1.setBackgroundColor(Color.parseColor("#D6C8BA"));
        c.addView(l1, new LinearLayout.LayoutParams(dp(2), 0, 1f));
        TextView t = text("복\n도", 12, Color.parseColor("#5E4C42"), true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(6), 0, dp(6));
        c.addView(t);
        View l2 = new View(this);
        l2.setBackgroundColor(Color.parseColor("#D6C8BA"));
        c.addView(l2, new LinearLayout.LayoutParams(dp(2), 0, 1f));
        return c;
    }

    private View legend(int color, String label, boolean dot) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(8), 0, 0, 0);
        View sw = new View(this);
        GradientDrawable g = new GradientDrawable();
        if (dot) {
            g.setShape(GradientDrawable.OVAL);
            g.setColor(color);
            sw.setLayoutParams(new LinearLayout.LayoutParams(dp(8), dp(8)));
        } else {
            g.setCornerRadius(dp(3));
            g.setColor(color);
            g.setStroke(dp(1), Color.parseColor("#A59A90"));
            sw.setLayoutParams(new LinearLayout.LayoutParams(dp(12), dp(12)));
        }
        sw.setBackground(g);
        l.addView(sw);
        TextView t = text(label, 11, MUTED, true);
        t.setPadding(dp(3), 0, 0, 0);
        l.addView(t);
        return l;
    }

    // ---------------------------------------------------------------- 설정 저장

    private void saveFromUi(boolean notify) {
        int count = cfg.count;
        try {
            count = Integer.parseInt(countEdit.getText().toString().trim());
        } catch (NumberFormatException ignored) {
        }
        count = Math.max(1, Math.min(30, count));
        boolean countChanged = count != cfg.count;
        if (count > cfg.count) {
            for (int i = cfg.count + 1; i <= count; i++) {
                cfg.selSpine.add(i);
                cfg.selAbd.add(i);
            }
        }
        cfg.count = count;
        String p = prefixEdit.getText().toString().trim();
        if (!p.isEmpty()) cfg.prefix = p;
        cfg.playId = playIdEdit.getText().toString().trim();
        String cm = changeEdit.getText().toString().trim();
        cfg.changeModeName = cm.isEmpty() ? "릴렉스 모드" : cm;
        cfg.changeModeNo = changeNoEdit.getText().toString().trim();
        cfg.changeSpeed = changeSpeedEdit.getText().toString().trim();
        cfg.save(this);
        if (countChanged) rebuildBeds();
        countEdit.setText(String.valueOf(cfg.count));
        if (notify) Toast.makeText(this, "저장했어요", Toast.LENGTH_SHORT).show();
    }

    private void shareDump() {
        shareText(lastDump, "세라젬 화면 분석");
    }

    private void shareText(String d, String subject) {
        if (d == null) return;
        if (d.length() > 150000) d = d.substring(0, 150000) + "\n…(생략)";
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("text/plain");
        s.putExtra(Intent.EXTRA_SUBJECT, subject);
        s.putExtra(Intent.EXTRA_TEXT, d);
        startActivity(Intent.createChooser(s, subject + " 보내기"));
    }

    // ---------------------------------------------------------------- 작은 위젯 도우미

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private TextView section(String s) {
        TextView t = text(s, 15, MUTED, true);
        t.setPadding(dp(4), dp(18), 0, dp(6));
        return t;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.parseColor("#EAE3DF"));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        c.setLayoutParams(lp);
        parent.addView(c);
        return c;
    }

    /** 위쪽 큰 버튼(끄기·켜기·실행): 아이콘 + 글자 */
    private Button topButton(String icon, String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(icon + "\n" + label);
        b.setTextSize(17);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(18));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(84), 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private Button lightButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setTextColor(INK);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#EAE3DF"));
        bg.setCornerRadius(dp(16));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    /** 모드 변경: 누를 때마다 저속 → 정속 → 고속. 마지막으로 보낸 단계를 점으로 보여 준다. */
    private View modeChangeButton() {
        LinearLayout box = new LinearLayout(this);
        modeChangeBox = box;
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        box.setLayoutParams(lp);
        box.setClickable(true);
        box.setOnClickListener(v -> cycleSpeed());
        modeChangeText = text("모드 변경 · 안 함", 12, Color.WHITE, true);
        modeChangeText.setGravity(Gravity.CENTER);
        modeChangeText.setSingleLine(true);
        box.addView(modeChangeText);
        speedDots = new LinearLayout(this);
        speedDots.setOrientation(LinearLayout.HORIZONTAL);
        speedDots.setGravity(Gravity.CENTER);
        for (int i = 0; i < 3; i++) {
            View d = new View(this);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(16), dp(6));
            dl.setMargins(dp(2), dp(4), dp(2), 0);
            speedDots.addView(d, dl);
        }
        box.addView(speedDots);
        return box;
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private EditText field(LinearLayout parent, String label, String value, boolean number) {
        TextView l = text(label, 13, MUTED, false);
        l.setPadding(0, dp(8), 0, 0);
        parent.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextSize(16);
        e.setInputType(number ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        parent.addView(e);
        return e;
    }
}
