package com.jinanhealing.ceragemmacro;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 세라젬 IoT 앱 화면을 읽어서 대신 눌러 주는 접근성 서비스.
 * 고정 대기 대신 "원하는 화면 요소가 나타날 때까지" 기다렸다가 동작한다.
 */
public class MacroService extends AccessibilityService {

    public static final int MODE_ON = 1;
    public static final int MODE_RUN = 2;
    public static final int MODE_OFF = 3;
    public static final int MODE_ON_RUN = 4;
    public static final int MODE_CHANGE = 5;
    public static final int MODE_CHANGE_FAST = 6;
    public static final int MODE_CHANGE_SLOW = 7;
    /** 수정 사항 적용: 선택을 바꾼 기기만 — 새로 고른 칸은 시작, 해제한 칸은 멈춤 */
    public static final int MODE_APPLY = 8;
    /** 점검: 세라젬 앱 화면을 읽어 일괄조작이 알고 있는 상태와 비교(아무것도 누르지 않음) */
    public static final int MODE_CHECK = 9;
    /** 이번 실행에만 쓰는 속도(모드 변경 저속/정속/고속). 비어 있으면 설정값. */
    private volatile String speedOverride = "";

    public static volatile MacroService instance;

    private static final String ID_POWER_OFF = "kr.co.ceragem.iot:id/do_power_off";
    private static final String ID_PLAY = "kr.co.ceragem.iot:id/control_play";
    private static final String ID_MODE_PAGES = "kr.co.ceragem.iot:id/summary_graph_pages";
    private static final String ID_MODE_TITLE = "kr.co.ceragem.iot:id/page_widget_03";
    private static final String ID_PROGRESS = "kr.co.ceragem.iot:id/massage_progress_message";
    private static final String ID_DETAIL_PAGES = "kr.co.ceragem.iot:id/master_v_pages";
    private static final String ID_DETAIL_BACK = "kr.co.ceragem.iot:id/do_back";

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private volatile boolean stopRequested = false;
    private Toast lastToast;
    /** 0 안정 · 1 보통 · 2 빠름 */
    private volatile int speed = 2;
    private long lastVolDown = 0;

    // ---- 문제 리포트(실패·앱 재실행 이유 기록)
    private final StringBuilder diag = new StringBuilder();
    private int issues = 0;
    private int diagAlwaysSnap = 0;
    private String relaunchReason = "처음 시작";

    /** 속도에 따라 대기 시간을 고른다. */
    private long pick(long safe, long normal, long fast) {
        return speed <= 0 ? safe : speed == 1 ? normal : fast;
    }

    private interface Finder {
        AccessibilityNodeInfo find();
    }

    private static class Result {
        static final int OK = 0, SKIP = 1, FAIL = 2;
        final int kind;
        final String msg;
        /** ▶처럼 두 번 누르면 반대로 동작하는 버튼을 이미 눌렀으면 자동 재시도하지 않는다. */
        boolean noRetry = false;

        Result(int kind, String msg) {
            this.kind = kind;
            this.msg = msg;
        }

        Result noRetry() {
            noRetry = true;
            return this;
        }

        /** 이 결과로 알게 된 기기 상태 변화(Config.ST_* 비트): 켜진 것 / 꺼진 것 */
        int setBits = 0, clearBits = 0;
        /** 점검 결과: 실제 상태 비트(-1 = 읽지 못함) */
        int actual = -1;

        /** 기기 상태 때문에 생긴 예상된 실패(예: 전원 꺼짐) — 재시도·리포트 안 함. */
        boolean expected = false;

        Result expected() {
            expected = true;
            noRetry = true;
            return this;
        }

        static Result ok(String m) { return new Result(OK, m); }
        static Result skip(String m) { return new Result(SKIP, m); }
        static Result fail(String m) { return new Result(FAIL, m); }
    }

    // ---------------------------------------------------------------- 생명주기

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    /** 실행 중 볼륨 아래 버튼 = 중지. */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (running && event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                long now = System.currentTimeMillis();
                if (now - lastVolDown < 1500) {
                    stopRequested = true;
                    toast("중지합니다 — 지금 기기까지만 하고 멈춰요");
                } else {
                    toast("한 번 더 누르면 중지해요");
                }
                lastVolDown = now;
            }
            return true;
        }
        return false;
    }

    public boolean isRunning() {
        return running;
    }

    public static String modeLabel(int mode) {
        switch (mode) {
            case MODE_ON: return "켜기";
            case MODE_RUN: return "실행";
            case MODE_OFF: return "끄기";
            case MODE_ON_RUN: return "켜고 바로 실행";
            case MODE_CHANGE: return "모드 변경";
            case MODE_CHANGE_FAST: return "모드 변경(고속)";
            case MODE_CHANGE_SLOW: return "모드 변경(저속)";
            case MODE_APPLY: return "수정 사항 적용";
            case MODE_CHECK: return "점검";
            default: return "?";
        }
    }

    // ---------------------------------------------------------------- 실행

    public synchronized boolean start(final int mode) {
        return start(mode, "");
    }

    /** speed: 모드 변경 때 이번에만 쓸 속도("저속" / "정속" / "고속"). 비우면 설정값. */
    public synchronized boolean start(final int mode, final String speedLabel) {
        if (running) return false;
        running = true;
        stopRequested = false;
        speedOverride = mode == MODE_CHANGE_FAST ? "고속" : mode == MODE_CHANGE_SLOW ? "저속"
                : (speedLabel == null ? "" : speedLabel);
        new Thread(() -> {
            String report;
            PowerManager.WakeLock wl = null;
            try {
                // 실행 중 화면이 꺼지면 터치가 안 되므로 화면을 켜 둔다(최대 15분)
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "ceragem:run");
                    wl.acquire(15 * 60 * 1000L);
                }
            } catch (Throwable ignored) {
            }
            try {
                if (mode == MODE_ON_RUN) {
                    report = runAll(MODE_ON);
                    if (!stopRequested) report = report + "\n" + runAll(MODE_RUN);
                } else {
                    report = runAll(mode);
                }
            } catch (Throwable t) {
                report = "오류가 발생했어요: " + t;
            } finally {
                running = false;
                try {
                    if (wl != null && wl.isHeld()) wl.release();
                } catch (Throwable ignored) {
                }
            }
            showInApp(report, null);
        }, "ceragem-macro").start();
        return true;
    }

    private String runAll(int mode) {
        Config c = Config.load(this);
        speed = c.speed;
        List<Integer> devices = devicesFor(c, mode);
        if (devices.isEmpty()) return mode == MODE_APPLY ? "수정한 기기가 없어요." : "선택된 기기가 없어요.";
        long t0 = System.currentTimeMillis();
        // 점검: 점검 전에 일괄조작이 알고 있던 상태 / 점검으로 알게 된 실제 상태
        final java.util.Map<Integer, Integer> expectedBefore = new java.util.TreeMap<>(c.status);
        final java.util.Map<Integer, Integer> actualNow = new java.util.TreeMap<>();
        final List<Integer> doneDevices = new ArrayList<>();   // 실패하지 않은 기기(기준 선택 갱신용)

        StringBuilder lines = new StringBuilder();
        int ok = 0, skip = 0, fail = 0;
        boolean relaunch = true; // 처음 한 번만 앱을 새로 연다
        diag.setLength(0);
        netAlerts = 0;
        issues = 0;
        diagAlwaysSnap = 0;
        relaunchReason = "처음 시작";
        log("시작: " + modeTitle(mode) + " · 기기 " + devices + " · 속도 " + speed
                + " · 척추 선택 " + c.selSpine + " · 복부 선택 " + c.selAbd);
        for (int k = 0; k < devices.size(); k++) {
            if (stopRequested) {
                lines.append("⏹ 사용자가 중지함\n");
                break;
            }
            String name = c.deviceName(devices.get(k));
            toast((k + 1) + "/" + devices.size() + "  " + name + "  " + modeLabel(mode) + " 중…");
            log("── " + name);
            final int dev = devices.get(k);
            Result r = doDevice(c, mode, name, relaunch, dev);
            if (r.kind == Result.FAIL && stopRequested) {
                log("⏹ 중지 요청으로 멈춤: " + r.msg);
            } else if (r.kind == Result.FAIL && r.expected) {
                log("❌ " + r.msg + " (기기 상태라 재시도 안 함)");
            } else if (r.kind == Result.FAIL) {
                issues++;
                log("❌ 실패: " + r.msg + (r.noRetry ? " (▶를 이미 눌러서 재시도 안 함)" : ""));
                snapshot(name + " 실패 직후");
            }
            if (r.kind == Result.FAIL && !r.noRetry && !stopRequested) {
                toast(name + " 다시 시도");
                relaunchReason = name + " 재시도 (" + r.msg + ")";
                r = doDevice(c, mode, name, true, dev);
                if (r.kind == Result.FAIL) {
                    log("❌ 재시도도 실패: " + r.msg);
                    snapshot(name + " 재시도 실패 직후");
                } else {
                    log("재시도 결과: " + r.msg);
                }
            } else if (r.kind != Result.FAIL) {
                log("결과: " + r.msg);
            }
            // 목록으로 빨리 돌아왔으면 다음 기기는 앱을 다시 열지 않는다
            relaunch = stopRequested || !returnToList(c);
            if (relaunch && !stopRequested && k < devices.size() - 1) {
                issues++;
                relaunchReason = name + " 뒤 목록으로 돌아오지 못함";
                log("⚠ 뒤로가기 3번으로도 목록에 못 돌아옴 → 다음 기기에서 세라젬 앱을 새로 엶");
                snapshot(name + " 뒤 목록 복귀 실패");
            }
            if (r.kind == Result.OK) ok++;
            else if (r.kind == Result.SKIP) skip++;
            else fail++;
            // 기기 상태 기억 (화면의 켜짐/실행 색)
            if (mode == MODE_CHECK) {
                if (r.actual >= 0) {
                    actualNow.put(dev, r.actual);
                    Config.updateStatus(this, dev, r.actual, Config.ST_ALL & ~r.actual);
                }
            } else if (r.setBits != 0 || r.clearBits != 0) {
                Config.updateStatus(this, dev, r.setBits, r.clearBits);
            }
            if (r.kind != Result.FAIL) doneDevices.add(dev);
            lines.append(r.kind == Result.OK ? "✅ " : r.kind == Result.SKIP ? "➖ " : "❌ ")
                    .append(name).append(" — ").append(r.msg).append('\n');
        }
        String when = new SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(new Date());
        long secs = (System.currentTimeMillis() - t0 + 500) / 1000;
        String[] sp = {"안정", "보통", "빠름"};
        String netNote = netAlerts > 0 ? "\n⚠ 세라젬 앱 네트워크 알림 " + netAlerts + "번 (통신 불안정) — 자동으로 닫음\n" : "";
        // 기준 선택 갱신: 실행 = 지금 선택을 더함 · 적용 = 선택과 똑같이 · 끄기 = 뺌
        if (mode == MODE_RUN || mode == MODE_ON_RUN) Config.baselineAdd(this, c, doneDevices);
        else if (mode == MODE_APPLY) Config.baselineSet(this, c, doneDevices);
        else if (mode == MODE_OFF) Config.baselineSet(this, c, doneDevices);   // 꺼진 뒤에는 지금 선택이 기준
        if (mode == MODE_CHECK) lines.append(finishCheck(c, expectedBefore, actualNow));
        String summary = "[" + when + "] " + modeTitle(mode) + " (" + sp[Math.max(0, Math.min(2, speed))] + ", " + secs + "초)\n"
                + "성공 " + ok + " · 건너뜀 " + skip + " · 실패 " + fail + "\n\n"
                + lines + netNote;
        boolean problem = fail > 0 || issues > 0 || netAlerts > 0 || lines.indexOf("확인 안 됨") >= 0;
        if (problem) recordFailHistory(mode, when, secs, lines.toString());
        if (issues == 0) {
            MainActivity.lastIssueReport = buildReport(summary);   // 문제가 없어도 진행 기록은 공유할 수 있게
        } else {
            String saved = saveIssueReport(summary);
            summary += "\n📝 문제 리포트 " + issues + "건 저장" + (saved != null ? " (" + saved + ")" : "")
                    + " — 아래 '진행 기록 공유'로 보내주세요";
        }
        return summary;
    }

    /** 모드마다 대상이 되는 기기: 켜기·끄기·실행 = 척추나 복부가 선택된 기기, 모드 변경 = 척추 선택, 적용 = 선택이 바뀐 기기, 점검 = 전부 */
    private List<Integer> devicesFor(Config c, int mode) {
        List<Integer> out = new ArrayList<>();
        for (int i = 1; i <= c.count; i++) {
            boolean sp = c.selSpine.contains(i), ab = c.selAbd.contains(i);
            if (mode == MODE_CHECK) out.add(i);
            else if (mode == MODE_APPLY) {
                if (c.modified(i)) out.add(i);
            } else if (mode == MODE_CHANGE || mode == MODE_CHANGE_FAST || mode == MODE_CHANGE_SLOW) {
                if (sp) out.add(i);
            } else if (sp || ab) out.add(i);
        }
        return out;
    }

    private static String modeTitle(int mode) {
        return (mode == MODE_APPLY || mode == MODE_CHECK ? "" : "전체 ") + modeLabel(mode);
    }

    /** doDeviceInner 결과에 '이 기기가 켜졌다/꺼졌다'는 상태 변화를 덧붙인다. */
    private Result doDevice(Config c, int mode, String name, boolean relaunch, int dev) {
        Result r = doDeviceInner(c, mode, name, relaunch, dev);
        if (mode == MODE_ON && r.kind != Result.FAIL) r.setBits |= Config.ST_POWER;
        else if (mode == MODE_OFF && r.kind != Result.FAIL) r.clearBits |= Config.ST_ALL;
        if (r.kind == Result.FAIL && r.expected && r.msg.contains("전원이 꺼져")) r.clearBits |= Config.ST_ALL;
        return r;
    }

    private Result doDeviceInner(Config c, int mode, String name, boolean relaunch, int dev) {
        if (relaunch && !launchTarget()) return Result.fail("세라젬 IoT 앱을 열 수 없음");

        // 수정 사항 적용: 이 기기에서 새로 시작할 페이지 / 멈출 페이지
        final List<String> startPages = new ArrayList<>(), stopPages = new ArrayList<>();
        if (mode == MODE_APPLY) {
            boolean sp = c.selSpine.contains(dev), ab = c.selAbd.contains(dev);
            if (sp && !c.baseSpine.contains(dev)) startPages.add("1");
            if (!sp && c.baseSpine.contains(dev)) stopPages.add("1");
            if (ab && !c.baseAbd.contains(dev)) startPages.add("2");
            if (!ab && c.baseAbd.contains(dev)) stopPages.add("2");
        }

        AccessibilityNodeInfo card = findCard(c, name, relaunch);
        if (stopRequested) return Result.fail("중지됨");
        if (card == null) return Result.fail("목록에서 기기를 찾지 못함");

        String status = collectText(card);
        log("목록 카드: " + clean(status));
        // 목록의 상태 글자는 앱을 막 열었을 때 '사용 가능'으로 먼저 나왔다가 늦게 '(전원 꺼짐)'으로 바뀐다.
        // 그래서 '전원 꺼짐'만 믿고, 켜짐 여부는 기기 화면에서 판단한다.
        boolean off = status.contains("전원 꺼짐");
        if (mode == MODE_ON) {
            // 목록만 보고 '이미 켜짐'으로 건너뛰지 않는다
        } else if (mode == MODE_OFF) {
            if (off) return Result.skip("이미 꺼져 있음");
        } else if (mode == MODE_CHECK) {
            if (off) {
                Result r = Result.skip("전원 꺼짐");
                r.actual = 0;
                return r;
            }
        } else if (mode == MODE_APPLY && startPages.isEmpty()) {
            // 멈추기만 하면 되는데 이미 꺼져 있으면 할 일이 없다
            if (off) {
                Result r = Result.skip("이미 꺼져 있어 멈출 것 없음");
                r.clearBits = Config.ST_ALL;
                return r;
            }
        } else {
            if (off) return Result.fail("전원이 꺼져 있음 — 먼저 켜기").expected();
        }

        // 기기 화면이 열렸는지: 꺼진 화면(가운데 전원) 또는 켜진 화면(오른쪽 위 전원·▶·뒤로) 중 하나.
        // 네트워크 알림 창이 터치를 가로채면 목록에 그대로 있으므로 카드를 다시 누른다(최대 3번).
        AccessibilityNodeInfo detail = null;
        for (int attempt = 1; attempt <= 3 && detail == null && !stopRequested; attempt++) {
            if (attempt > 1) {
                if (!isListScreen(c)) break;
                AccessibilityNodeInfo again = findExactText(name);
                if (again == null) break;
                card = containerOf(again);
                log("기기 화면이 안 열려 카드 다시 누름 (" + attempt + "번째)");
            }
            if (!clickNode(card)) return Result.fail("기기 카드를 누르지 못함");
            detail = waitFor(() -> {
                AccessibilityNodeInfo n = findById(c.powerId);
                if (n == null) n = findById(ID_POWER_OFF);
                if (n == null) n = findById(ID_PLAY);
                if (n == null) n = findById(ID_DETAIL_PAGES);
                if (n == null) n = findById(ID_DETAIL_BACK);
                if (n == null) n = textContaining("연결이 해제");
                return n;
            }, attempt < 3 ? 5000 : 8000);
        }
        if (detail == null) {
            Result lost = checkDisconnected();
            if (lost != null) return lost;
            return Result.fail("기기 화면이 열리지 않음");
        }
        log("기기 화면 열림 (" + detail.getViewIdResourceName() + ")");
        {
            Result lost = checkDisconnected();
            if (lost != null) return lost;
        }
        // 실행·모드 변경: 기기 화면이 '전원을 켜 주세요'면 꺼진 것(목록 글자가 늦게 바뀐 경우)
        if (mode != MODE_ON && mode != MODE_OFF) {
            sleep(pick(500, 300, 150));
            if (visibleText("전원을 켜 주세요") != null || visibleText("전원을 켜주세요") != null) {
                if (mode == MODE_CHECK) {
                    Result r = Result.skip("전원 꺼짐");
                    r.actual = 0;
                    return r;
                }
                if (mode == MODE_APPLY && startPages.isEmpty()) {
                    Result r = Result.skip("이미 꺼져 있어 멈출 것 없음");
                    r.clearBits = Config.ST_ALL;
                    return r;
                }
                return Result.fail("전원이 꺼져 있음 — 먼저 켜기").expected();
            }
        }

        if (mode == MODE_ON) {
            // 화면 내용이 다 뜰 때까지: 가운데 전원(꺼짐) 또는 오른쪽 위 전원(켜짐)이 보일 때까지 기다린다
            waitFor(() -> {
                AccessibilityNodeInfo a1 = findById(c.powerId);
                if (a1 != null && a1.isVisibleToUser()) return a1;
                AccessibilityNodeInfo a2 = findById(ID_POWER_OFF);
                if (a2 != null && a2.isVisibleToUser()) return a2;
                return textContaining("켜는 중");
            }, 3000);
            sleep(pick(500, 250, 100));
            if (textContaining("켜는 중") != null) return Result.skip("이미 켜는 중");
            // findById는 화면에 안 보이는 버튼도 돌려줄 수 있다 → 켜진 기기에서 숨은 버튼을 눌러 꺼 버리지 않도록
            // '보이는' 가운데 전원 버튼일 때만 누른다
            AccessibilityNodeInfo power = findById(c.powerId);
            if (power != null && !power.isVisibleToUser()) power = null;
            boolean offMsg = visibleText("전원을 켜 주세요") != null || visibleText("전원을 켜주세요") != null;
            AccessibilityNodeInfo onBtn = findById(ID_POWER_OFF);
            boolean onScreen = onBtn != null && onBtn.isVisibleToUser();
            if (power == null && !offMsg) {
                return onScreen ? Result.skip("이미 켜져 있음") : Result.fail("기기 상태를 확인하지 못함");
            }
            if (power == null) return Result.fail("가운데 전원 버튼을 찾지 못함");
            if (onScreen && !offMsg) return Result.skip("이미 켜져 있음");
            if (!clickNode(power)) return Result.fail("전원 버튼을 누르지 못함");
            // 켜지면 오른쪽 위 전원 버튼이 생기고 '전원을 켜 주세요'가 사라진다
            AccessibilityNodeInfo on = waitFor(() -> {
                AccessibilityNodeInfo b = findById(ID_POWER_OFF);
                boolean msg = visibleText("전원을 켜 주세요") != null || visibleText("전원을 켜주세요") != null;
                if (b != null && b.isVisibleToUser() && !msg) return b;
                return textContaining("켜는 중");     // '전원을 켜는 중입니다' — 켜지는 중
            }, pick(5000, 4000, 3000));
            if (on != null) return Result.ok(on.getViewIdResourceName() != null && on.getViewIdResourceName().endsWith("do_power_off")
                    ? "켰음" : "켜는 중");
            issues++;
            log("전원 버튼을 눌렀지만 켜진 화면이 확인되지 않음");
            snapshot("켜기 확인 실패");
            return Result.ok("전원 버튼 누름 (켜짐 확인 안 됨)");
        }
        if (mode == MODE_OFF) {
            sleep(pick(800, 400, 200));
            if (visibleText("전원을 켜 주세요") != null || visibleText("전원을 켜주세요") != null) {
                return Result.skip("이미 꺼져 있음");
            }
            AccessibilityNodeInfo offBtn = findOffButton();
            if (offBtn != null && !offBtn.isVisibleToUser()) {
                // 마사지 진행 화면이 앞에 있으면 첫 화면으로 되돌린 뒤 다시 찾는다
                AccessibilityNodeInfo pages = findById(ID_DETAIL_PAGES);
                if (pages != null) {
                    pages.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                    sleep(pick(1200, 900, 700));
                    AccessibilityNodeInfo again = findOffButton();
                    if (again != null) offBtn = again;
                }
            }
            if (offBtn == null) return Result.fail("오른쪽 위 전원 버튼을 찾지 못함");
            if (!offBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK) && !clickNode(offBtn)) {
                return Result.fail("전원 버튼을 누르지 못함");
            }
            confirmDialogIfAny();
            AccessibilityNodeInfo done = waitFor(() -> {
                AccessibilityNodeInfo a = visibleText("전원을 켜 주세요");
                if (a == null) a = visibleText("전원을 켜주세요");
                if (a == null) a = textContaining("끄는 중");
                return a;
            }, pick(7000, 6000, 5000));
            return done != null ? Result.ok("껐음") : Result.ok("전원 버튼 누름 (꺼짐 확인 안 됨)");
        }
        if (mode == MODE_CHANGE || mode == MODE_CHANGE_FAST || mode == MODE_CHANGE_SLOW) return changeSpineMode(c);
        if (mode == MODE_CHECK) return checkDevice(c);
        if (mode == MODE_APPLY) return applyDevice(c, startPages, stopPages);
        return runMassage(c, dev);
    }

    private static final String ID_MODES_BTN = "kr.co.ceragem.iot:id/open_body_parts_modes";
    private static final String ID_SPINE_MODES_BTN = "kr.co.ceragem.iot:id/open_body_parts_spine_modes";
    private static final String ID_SHEET_DONE = "kr.co.ceragem.iot:id/do_positive";
    private static final String ID_SHEET_ITEM_TITLE = "kr.co.ceragem.iot:id/item_widget_03";

    /** 모드 선택 창의 항목 제목. 예: "[표준]2.릴렉스" → 번호 "2" 또는 이름 "릴렉스"가 맞는 것. */
    private AccessibilityNodeInfo findSheetItem(String no, String name) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> titles = root.findAccessibilityNodeInfosByViewId(ID_SHEET_ITEM_TITLE);
        if (titles == null) return null;
        AccessibilityNodeInfo byName = null;
        for (AccessibilityNodeInfo t : titles) {
            if (!t.isVisibleToUser() || t.getText() == null) continue;
            String raw = t.getText().toString();
            String body = raw.contains("]") ? raw.substring(raw.indexOf(']') + 1).trim() : raw.trim();
            String n = norm(body);
            if (!no.isEmpty() && n.startsWith(no + ".")) {
                if (name.isEmpty() || n.contains(norm(name))) return t;   // 번호와 이름 둘 다 맞으면 바로
                if (byName == null) byName = t;
            } else if (!name.isEmpty() && n.contains(norm(name)) && byName == null) {
                byName = t;
            }
        }
        return byName;
    }

    private AccessibilityNodeInfo visibleDesc(String desc) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        String want = norm(desc);
        for (AccessibilityNodeInfo n : allNodes(root)) {
            if (n.isVisibleToUser() && norm(n.getContentDescription()).equals(want)) return n;
        }
        return null;
    }

    /** 척추(첫 번째) 페이지의 모드를 설정한 모드(기본: 릴렉스 모드)로 바꾼다. ▶는 누르지 않는다. */
    private Result changeSpineMode(Config c) {
        final String target = c.changeModeName == null || c.changeModeName.trim().isEmpty()
                ? "릴렉스 모드" : c.changeModeName.trim();
        final String shortName = target.replace("모드", "").trim();   // "릴렉스"
        sleep(pick(1200, 800, 500));
        showModePages();

        // 모드 넘기기 화면을 맨 앞(척추 페이지)으로, 이름이 자리 잡을 때까지 확인
        goToFirstPage();
        final String no = c.changeModeNo == null ? "" : c.changeModeNo.trim();

        // 아래쪽 '모드' 버튼 (숫자가 현재 모드 번호, 릴렉스 = 2)
        AccessibilityNodeInfo btn = findCenteredOrVisible(ID_MODES_BTN);
        if (btn == null) btn = findCenteredOrVisible(ID_SPINE_MODES_BTN);
        if (btn == null) return Result.fail("모드 버튼을 찾지 못함");
        if ((!no.isEmpty() && no.equals(norm(btn.getText()))) || isModeCentered(target)) {
            String spMsg = applySpeed(c);
            String base = "이미 " + target + (no.isEmpty() ? "" : " (모드 " + no + ")");
            return spMsg.startsWith("속도 ") && !spMsg.contains("이미") && !spMsg.contains("못")
                    ? Result.ok(base + " / " + spMsg) : Result.skip(base + (spMsg.isEmpty() ? "" : " / " + spMsg));
        }
        if (!btn.performAction(AccessibilityNodeInfo.ACTION_CLICK) && !clickNode(btn)) {
            return Result.fail("모드 버튼을 누르지 못함");
        }

        // 모드 선택 창(아래에서 올라오는 시트)이 뜰 때까지
        AccessibilityNodeInfo sheet = waitFor(() -> {
            AccessibilityNodeInfo n = findById(ID_SHEET_DONE);
            return n != null ? n : findById(ID_SHEET_ITEM_TITLE);
        }, 4000);
        if (sheet == null) {
            log("모드 선택 창이 뜨지 않음");
            snapshot("모드 버튼 누른 뒤");
            return Result.fail("모드 선택 창이 열리지 않음");
        }
        sleep(pick(500, 300, 200));
        // 척추 모드 창이 맞는지(음파 진동 페이지의 모드 창이면 잘못 바꾸지 않도록 닫는다)
        if (findById("kr.co.ceragem.iot:id/master_v_body_parts_spine_modes") == null
                && findById("kr.co.ceragem.iot:id/master_v_body_parts_spine_auto_modes") == null) {
            log("열린 창이 척추 모드 창이 아님 — 닫고 중단");
            snapshot("척추 모드 창 아님");
            performGlobalAction(GLOBAL_ACTION_BACK);
            return Result.fail("척추 모드 창이 아님 (첫 페이지로 못 감)");
        }

        // '자동 모드' 탭이 선택돼 있지 않으면 누른다(선택된 탭은 누를 수 없는 상태로 나옴)
        AccessibilityNodeInfo autoTab = visibleDesc("자동 모드");
        if (autoTab != null && autoTab.isClickable()) {
            autoTab.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            sleep(pick(800, 500, 350));
        }

        // "[표준]2.릴렉스" 같은 항목 찾기 — 번호("2.") 또는 이름("릴렉스")으로
        AccessibilityNodeInfo item = waitFor(() -> findSheetItem(no, shortName), 3000);
        if (item == null) {
            log("모드 목록에서 '" + no + "." + shortName + "' 항목을 찾지 못함");
            snapshot("모드 선택 창");
            performGlobalAction(GLOBAL_ACTION_BACK);
            return Result.fail("모드 목록에서 '" + shortName + "'을 찾지 못함");
        }
        log("모드 항목 선택: " + item.getText());
        // 줄 전체(누를 수 있는 부모)를 누른다. 오른쪽 ＋ 버튼은 누르지 않는다.
        AccessibilityNodeInfo row = item.getParent();
        boolean picked = row != null && row.isClickable() && row.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!picked) picked = clickNode(item);
        if (!picked) return Result.fail("모드 항목을 누르지 못함");
        sleep(pick(700, 450, 300));

        // ✓ 완료
        AccessibilityNodeInfo doneBtn = findById(ID_SHEET_DONE);
        if (doneBtn == null) doneBtn = visibleTextContaining("완료");
        if (doneBtn != null) {
            if (!doneBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) clickNode(doneBtn);
        } else {
            log("완료 버튼을 찾지 못함");
        }
        sleep(pick(800, 500, 300));
        AccessibilityNodeInfo done = waitFor(() -> {
            if (isModeCentered(target)) return getRootInActiveWindow();
            AccessibilityNodeInfo b = findCenteredOrVisible(ID_MODES_BTN);
            if (b == null) b = findCenteredOrVisible(ID_SPINE_MODES_BTN);
            return (b != null && !no.isEmpty() && no.equals(norm(b.getText()))) ? b : null;
        }, 4000);
        if (done != null) {
            // 모드를 바꾸면 속도가 '정속'으로 돌아가므로, 설정한 속도로 다시 맞춘다
            String spMsg = applySpeed(c);
            return Result.ok(target + "로 변경" + (spMsg.isEmpty() ? "" : " / " + spMsg));
        }
        issues++;
        log("모드를 골랐지만 모드 번호가 " + no + "로 바뀐 것을 확인하지 못함");
        snapshot("모드 변경 확인 실패");
        return Result.skip(shortName + " 선택함 — 변경 확인 안 됨");
    }

    private static final String ID_SPEED_BTN = "kr.co.ceragem.iot:id/open_spine_mode_speeds";

    /** 속도 버튼 → 저속/정속/고속 고르고 ✓ 완료. 설정이 비어 있으면 아무것도 안 함. */
    private String applySpeed(Config c) {
        String sp = !speedOverride.isEmpty() ? speedOverride : (c.changeSpeed == null ? "" : c.changeSpeed.trim());
        String rid;
        if (sp.contains("저")) rid = "kr.co.ceragem.iot:id/speed_low";
        else if (sp.contains("정")) rid = "kr.co.ceragem.iot:id/speed_constant";
        else if (sp.contains("고")) rid = "kr.co.ceragem.iot:id/speed_high";
        else return "";
        sleep(pick(800, 500, 300));
        AccessibilityNodeInfo btn = findCenteredOrVisible(ID_SPEED_BTN);
        if (btn == null) {
            log("속도 버튼을 찾지 못함");
            snapshot("속도 버튼 찾기");
            return "속도 버튼 못 찾음";
        }
        if (!btn.performAction(AccessibilityNodeInfo.ACTION_CLICK) && !clickNode(btn)) return "속도 버튼 못 누름";
        final String ridF = rid;
        AccessibilityNodeInfo radio = waitFor(() -> findById(ridF), 3000);
        if (radio == null) {
            log("속도 선택 창이 뜨지 않음");
            snapshot("속도 버튼 누른 뒤");
            performGlobalAction(GLOBAL_ACTION_BACK);
            return "속도 창 못 엶";
        }
        sleep(pick(400, 250, 150));
        // 체크 상태 표시는 믿을 수 없어서 항상 누른다(이미 선택된 것을 누르면 아무 변화 없음)
        boolean was = false;
        if (!radio.performAction(AccessibilityNodeInfo.ACTION_CLICK)) clickNode(radio);
        sleep(pick(500, 300, 200));
        AccessibilityNodeInfo doneBtn = findById(ID_SHEET_DONE);
        if (doneBtn != null && !doneBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) clickNode(doneBtn);
        sleep(pick(800, 500, 300));
        String label = sp.contains("저") ? "저속" : sp.contains("정") ? "정속" : "고속";
        log("속도 " + label + (was ? " (이미 선택돼 있었음)" : " 선택"));
        return was ? "속도 이미 " + label : "속도 " + label;
    }

    private AccessibilityNodeInfo findCenteredOrVisible(String id) {
        AccessibilityNodeInfo n = findCenteredById(id);
        if (n != null) return n;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
        if (found == null) return null;
        for (AccessibilityNodeInfo f : found) if (f.isVisibleToUser()) return f;
        return null;
    }
    private AccessibilityNodeInfo visibleTextContaining(String part) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(part);
        if (found == null) return null;
        for (AccessibilityNodeInfo n : found) if (n.isVisibleToUser()) return n;
        return null;
    }

    private static final String ID_MODE_STATUS = "kr.co.ceragem.iot:id/page_widget_04";

    /** 설정한 모드들(쉼표 구분)을 순서대로 넘겨 가며 각각 ▶를 누른다. 예: 릴렉스 모드 → 음파 진동 마사지1 */
    private Result runMassage(Config c, int dev) {
        sleep(pick(1500, 1000, 700));
        List<String> pg = c.pagesFor(dev);
        if (pg.isEmpty()) return Result.skip("선택된 부위 없음");
        return runPages(c, pg, null, false);
    }

    /** 척추·복부를 따로 고르게 되면서 쓰지 않는 옛 방식(모드 이름으로 찾기). */
    private Result runMassageLegacy(Config c) {
        sleep(pick(1500, 1000, 700));
        List<String> modes = new ArrayList<>();
        if (c.modeName != null) {
            for (String m : c.modeName.split("[,，]")) if (!m.trim().isEmpty()) modes.add(m.trim());
        }
        if (modes.isEmpty()) {
            if (isMassageRunning()) return Result.skip("이미 작동 중");
            AccessibilityNodeInfo play = findPlayForMode(c, "");
            if (play == null) return Result.fail("▶ 버튼을 찾지 못함");
            if (!play.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return Result.fail("▶ 버튼을 누르지 못함");
            AccessibilityNodeInfo st = waitFor(() -> isMassageRunning() ? getRootInActiveWindow() : null, 7000);
            return st != null ? Result.ok("작동 시작") : Result.skip("▶ 누름 — 확인 안 됨");
        }

        // 숫자만 적혀 있으면(예: "1, 2") 모드 이름과 상관없이 페이지 순서로 실행
        boolean byPage = true;
        for (String m : modes) if (!m.matches("\\d+")) byPage = false;
        if (byPage) return runPages(c, modes, null, false);

        List<String> started = new ArrayList<>(), already = new ArrayList<>(), unsure = new ArrayList<>(), failed = new ArrayList<>();
        for (final String mode : modes) {
            if (stopRequested) break;
            showModePages();
            if (!selectMode(mode)) {
                failed.add(mode + " 못 찾음");
                continue;
            }
            sleep(pick(500, 250, 120));
            if (isModeActive(mode)) {
                already.add(mode);
                continue;
            }
            AccessibilityNodeInfo play = findPlayForMode(c, mode);
            if (play == null) {
                failed.add(mode + " ▶ 못 찾음");
                continue;
            }
            if (!play.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                failed.add(mode + " ▶ 누르기 실패");
                continue;
            }
            // '마사지 대기'에서 다른 글자(스캔 대기 · 척추 스캔중 · 마사지 중)로 바뀌면 시작된 것
            AccessibilityNodeInfo ok = waitFor(() -> isModeActive(mode) ? getRootInActiveWindow() : null,
                    pick(5000, 3500, 2500));
            // 눌렀으면 확인이 안 되더라도 다시 누르지 않는다(두 번 누르면 멈춤)
            if (ok != null) started.add(mode);
            else unsure.add(mode);
            sleep(pick(1200, 600, 250));
        }

        StringBuilder msg = new StringBuilder();
        if (!started.isEmpty()) msg.append(String.join(" + ", started)).append(" 작동 시작");
        if (!already.isEmpty()) msg.append(msg.length() > 0 ? " / " : "").append(String.join(" + ", already)).append(" 이미 작동 중");
        if (!unsure.isEmpty()) msg.append(msg.length() > 0 ? " / " : "").append(String.join(" + ", unsure)).append(" ▶ 누름(확인 안 됨)");
        if (!failed.isEmpty()) {
            msg.append(msg.length() > 0 ? " / " : "").append(String.join(", ", failed));
            Result f = Result.fail(msg.toString());
            return (started.isEmpty() && unsure.isEmpty()) ? f : f.noRetry();
        }
        if (started.isEmpty() && unsure.isEmpty()) return Result.skip(msg.toString());
        return Result.ok(msg.toString());
    }

    /** 첫 번째 페이지(척추)부터 차례로, 적힌 번호의 페이지에서 ▶를 누른다. 모드 이름은 보지 않는다. */
    private Result runPages(Config c, List<String> pageList, java.util.Set<Integer> stopSet, boolean readOnly) {
        List<Integer> pages = new ArrayList<>();
        for (String s : pageList) {
            int n;
            try {
                n = Integer.parseInt(s);
            } catch (NumberFormatException e) {
                continue;
            }
            if (n >= 1 && n <= 5 && !pages.contains(n)) pages.add(n);
        }
        if (pages.isEmpty()) return Result.fail("실행할 페이지 번호가 잘못됨 (설정 확인)");
        java.util.Collections.sort(pages);
        String pid = (c.playId == null || c.playId.trim().isEmpty()) ? ID_PLAY : c.playId.trim();

        showModePages();
        goToFirstPage();
        List<String> started = new ArrayList<>(), already = new ArrayList<>(), unsure = new ArrayList<>(), failed = new ArrayList<>();
        List<String> stopped = new ArrayList<>(), idle = new ArrayList<>(), seen = new ArrayList<>();
        int setB = 0, clrB = 0;       // 알게 된 상태 변화 (Config.ST_*)
        boolean pressedStop = false;  // 멈추려고 ▶를 이미 눌렀으면 자동 재시도 금지(한 번 더 누르면 다시 시작됨)
        int readBits = 0, readMask = 0;   // 점검: 작동 중인 부위 / 읽은 부위
        // 지나온 페이지의 모드 이름(같은 페이지를 두 번 누르지 않기 위해)
        final java.util.Set<String> visited = new java.util.HashSet<>();
        int current = 1;
        String first = centeredTitle();
        if (!first.isEmpty()) visited.add(first);
        for (int target : pages) {
            if (stopRequested) break;
            showModePages();
            // 페이지 이름이 화면에 자리 잡을 때까지(진행 화면에서 돌아온 직후 등)
            waitFor(() -> centeredTitle().isEmpty() ? null : getRootInActiveWindow(), 3000);
            // 한 칸씩 넘기고, 가운데 모드 이름이 '처음 보는 이름'으로 바뀐 것을 확인한다
            while (current < target && !stopRequested) {
                final String before = centeredTitle();
                if (before.isEmpty()) {
                    log("⚠ 가운데 모드 이름을 읽지 못해 넘기지 않음");
                    snapshot("모드 이름 읽기 실패");
                    break;
                }
                visited.add(before);
                if (!scrollModePager(true)) swipeCarousel(true);
                AccessibilityNodeInfo moved = waitFor(() -> {
                    String t = centeredTitle();
                    return (!t.isEmpty() && !visited.contains(t)) ? getRootInActiveWindow() : null;
                }, pick(2500, 2000, 1500));
                if (moved == null) {
                    swipeCarousel(true);   // 한 번 더 손가락으로 밀어 본다
                    moved = waitFor(() -> {
                        String t = centeredTitle();
                        return (!t.isEmpty() && !visited.contains(t)) ? getRootInActiveWindow() : null;
                    }, pick(2500, 2000, 1500));
                }
                if (moved == null) {
                    log("⚠ " + (current + 1) + "번 페이지로 넘어가지 않음 (가운데 '" + centeredTitle() + "')");
                    snapshot((current + 1) + "번 페이지 넘김 실패");
                    break;
                }
                current++;
                log(current + "번 페이지로 넘김: '" + before + "' → '" + centeredTitle() + "'");
            }
            if (current != target) {
                issues++;
                failed.add(target + "번 페이지로 못 넘어감");
                continue;
            }
            // ▶가 '지금 가운데 이름의 페이지' 안에서 자리 잡을 때까지 기다린다
            final String pidW = pid;
            final String want = centeredTitle();
            AccessibilityNodeInfo play = waitFor(() -> {
                AccessibilityNodeInfo p = findCenteredById(pidW);
                if (p == null) return null;
                String t = pageTexts(p)[0].trim();
                return (t.isEmpty() || t.equals(want)) ? p : null;
            }, pick(3000, 2500, 2000));
            if (play == null) {
                issues++;
                log(target + "번 페이지('" + want + "') ▶ 못 찾음");
                snapshot(target + "번 페이지 ▶ 못 찾음");
                failed.add(target + "번 페이지 ▶ 못 찾음");
                continue;
            }
            if (target > 1 && visited.contains(pageTexts(play)[0].trim()) && !pageTexts(play)[0].trim().equals(want)) {
                issues++;
                log("⚠ " + target + "번 페이지 ▶가 앞 페이지 것으로 보여 누르지 않음");
                snapshot(target + "번 페이지 ▶ 확인 실패");
                failed.add(target + "번 페이지 확인 실패");
                continue;
            }
            visited.add(want);
            String[] ts = pageTexts(play);
            String label = ts[0].isEmpty() ? target + "번" : ts[0];
            String st = norm(ts[1]);
            log(target + "번 페이지: '" + ts[0] + "' 상태 '" + ts[1] + "'");
            final int bit = target == 1 ? Config.ST_SPINE : target == 2 ? Config.ST_ABD : 0;
            final boolean isStop = stopSet != null && stopSet.contains(target);
            if (readOnly || isStop) {
                // 척추(1번): 대기가 아닌 모든 글자(스캔 대기·척추 스캔중·마사지 중) = 작동 중
                // 그 밖의 페이지: '마사지 중'일 때만 작동 중 (다른 글자는 앞 페이지 상태가 비친 것)
                boolean runningNow = target == 1 ? (!st.isEmpty() && !st.equals("마사지대기")) : st.equals("마사지중");
                if (readOnly) {
                    readMask |= bit;
                    if (runningNow) readBits |= bit;
                    seen.add(label + (runningNow ? " 작동 중" : " 멈춤"));
                    continue;
                }
                if (!runningNow) {
                    idle.add(label);
                    clrB |= bit;
                    continue;
                }
                if (!play.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    failed.add(label + " 멈추기 누르기 실패");
                    continue;
                }
                pressedStop = true;
                sleep(pick(600, 400, 250));
                confirmDialogIfAny();
                final String pidStop = pid;
                AccessibilityNodeInfo done = waitFor(() -> {
                    AccessibilityNodeInfo p = findCenteredById(pidStop);
                    if (p == null) return null;
                    String[] x = pageTexts(p);
                    return (x[0].trim().equals(want) && norm(x[1]).equals("마사지대기")) ? p : null;
                }, pick(6000, 4500, 3500));
                AccessibilityNodeInfo pa = findCenteredById(pidStop);
                log(label + " 멈춤 ▶ 누른 뒤 상태: '" + (pa == null ? (isMassageRunning() ? "진행 화면" : "?") : pageTexts(pa)[1]) + "'");
                if (done != null) {
                    stopped.add(label);
                    clrB |= bit;
                } else {
                    failed.add(label + " 멈춤 확인 안 됨 (▶는 눌렀음 — 점검으로 확인)");
                    issues++;
                    snapshot(label + " 멈춤 확인 안 됨");
                }
                sleep(pick(1200, 600, 250));
                continue;
            }
            // 2번째 이후 페이지(음파 진동 등): '마사지 중'일 때만 작동 중으로 보고,
            // 그 밖의 글자(마사지 대기, 앞 페이지에서 비친 스캔 대기·척추 스캔중 등)면 기다리지 않고 바로 ▶
            if (target > 1 && !st.equals("마사지중") && !st.equals("마사지대기")) {
                // 앞 페이지의 '스캔 대기'가 비친 상태에서는 ▶가 먹지 않는다(10/22 기록: 03·08·09).
                // 고정 대기가 아니라 '마사지 대기'로 바뀌는 즉시 넘어간다.
                final String pidS = pid, wantS = want;
                long t0 = System.currentTimeMillis();
                AccessibilityNodeInfo settled = waitFor(() -> {
                    AccessibilityNodeInfo p = findCenteredById(pidS);
                    if (p == null) return null;
                    String[] x = pageTexts(p);
                    String n = norm(x[1]);
                    return (x[0].trim().equals(wantS) && (n.equals("마사지대기") || n.equals("마사지중"))) ? p : null;
                }, 4000);
                if (settled != null) {
                    play = settled;
                    st = norm(pageTexts(play)[1]);
                    log(target + "번 페이지 '" + ts[1] + "' → '" + pageTexts(play)[1] + "' ("
                            + (System.currentTimeMillis() - t0) + "ms)");
                } else {
                    log(target + "번 페이지가 계속 '" + ts[1] + "' — 그대로 ▶");
                    st = "마사지대기";
                }
            }
            if (target > 1 && !st.equals("마사지중")) st = "마사지대기";
            if (!st.isEmpty() && !st.equals("마사지대기")) {
                already.add(label);
                setB |= bit;
                if (diagAlwaysSnap < 2) {   // 한 번 실행에 두 번까지만 화면 기록
                    diagAlwaysSnap++;
                    snapshot(label + " '이미 작동 중'으로 판단한 화면");
                }
                continue;
            }
            if (!play.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                failed.add(label + " ▶ 누르기 실패");
                continue;
            }
            final String pidF = pid;
            AccessibilityNodeInfo ok = waitFor(() -> {
                AccessibilityNodeInfo p = findCenteredById(pidF);
                if (p == null) return isMassageRunning() ? getRootInActiveWindow() : null;
                String now = norm(pageTexts(p)[1]);
                if (target > 1 && now.contains("스캔")) return null;   // 앞 페이지 상태가 비친 것
                return (!now.isEmpty() && !now.equals("마사지대기")) ? p : null;
            }, pick(5000, 3500, 2500));
            {
                AccessibilityNodeInfo pa = findCenteredById(pidF);
                log(label + " ▶ 누른 뒤 상태: '" + (pa == null ? (isMassageRunning() ? "진행 화면" : "?") : pageTexts(pa)[1]) + "'");
                // 확인이 안 됐고 지금 화면이 분명히 '마사지 대기'(작동 안 함)이면 한 번 더 누른다 — 멈출 위험 없음
                if (ok == null && pa != null && norm(pageTexts(pa)[1]).equals("마사지대기")
                        && pageTexts(pa)[0].trim().equals(want) && !stopRequested) {
                    log(label + " 아직 '마사지 대기' → ▶ 한 번 더");
                    if (!pa.performAction(AccessibilityNodeInfo.ACTION_CLICK)) clickNode(pa);
                    ok = waitFor(() -> {
                        AccessibilityNodeInfo p = findCenteredById(pidF);
                        if (p == null) return isMassageRunning() ? getRootInActiveWindow() : null;
                        String now = norm(pageTexts(p)[1]);
                        if (target > 1 && now.contains("스캔")) return null;
                        return (!now.isEmpty() && !now.equals("마사지대기")) ? p : null;
                    }, pick(5000, 3500, 2500));
                    AccessibilityNodeInfo pb = findCenteredById(pidF);
                    log(label + " 두 번째 ▶ 뒤 상태: '" + (pb == null ? "?" : pageTexts(pb)[1]) + "'");
                }
            }
            if (ok != null) {
                started.add(label);
                setB |= bit;
            } else {
                unsure.add(label);
                setB |= bit;      // 눌렀으니 시작됐을 가능성이 크다 — 틀리면 점검에서 드러난다
                issues++;
                log(label + " ▶ 누른 뒤 상태 변화 확인 안 됨");
                snapshot(label + " ▶ 확인 안 됨");
            }
            sleep(pick(1200, 600, 250));
        }

        if (readOnly) {
            Result r;
            String m = seen.isEmpty() ? "" : String.join(" / ", seen);
            if (!failed.isEmpty()) m += (m.isEmpty() ? "" : " / ") + String.join(", ", failed);
            if (readMask == (Config.ST_SPINE | Config.ST_ABD)) {
                r = Result.ok(m);
                r.actual = Config.ST_POWER | readBits;
            } else {
                r = Result.fail(m.isEmpty() ? "화면을 읽지 못함" : m + " (일부만 읽음)");
            }
            return r;
        }

        StringBuilder msg = new StringBuilder();
        if (!started.isEmpty()) msg.append(String.join(" + ", started)).append(" 작동 시작");
        if (!stopped.isEmpty()) msg.append(msg.length() > 0 ? " / " : "").append(String.join(" + ", stopped)).append(" 멈춤");
        if (!already.isEmpty()) msg.append(msg.length() > 0 ? " / " : "").append(String.join(" + ", already)).append(" 이미 작동 중");
        if (!idle.isEmpty()) msg.append(msg.length() > 0 ? " / " : "").append(String.join(" + ", idle)).append(" 이미 멈춰 있음");
        if (!unsure.isEmpty()) msg.append(msg.length() > 0 ? " / " : "").append(String.join(" + ", unsure)).append(" 누름(확인 안 됨)");
        if (setB != 0) setB |= Config.ST_POWER;
        Result res;
        if (!failed.isEmpty()) {
            msg.append(msg.length() > 0 ? " / " : "").append(String.join(", ", failed));
            Result f = Result.fail(msg.toString());
            res = (started.isEmpty() && unsure.isEmpty() && stopped.isEmpty() && !pressedStop) ? f : f.noRetry();
        } else if (started.isEmpty() && unsure.isEmpty() && stopped.isEmpty()) {
            res = Result.skip(msg.toString());
        } else {
            res = Result.ok(msg.toString());
        }
        res.setBits = setB;
        res.clearBits = clrB;
        return res;
    }

    /** 수정 사항 적용: 새로 고른 부위는 시작하고, 해제한 부위는 멈춘다. (이미 그 상태면 누르지 않는다) */
    private Result applyDevice(Config c, List<String> startPages, List<String> stopPages) {
        sleep(pick(1500, 1000, 700));
        List<String> all = new ArrayList<>(startPages);
        all.addAll(stopPages);
        if (all.isEmpty()) return Result.skip("바뀐 부분 없음");
        java.util.Set<Integer> stopSet = new java.util.TreeSet<>();
        for (String x : stopPages) stopSet.add(Integer.parseInt(x));
        return runPages(c, all, stopSet, false);
    }

    /** 점검: 누르지 않고 척추(1번)·복부(2번) 페이지의 상태 글자만 읽는다. */
    private Result checkDevice(Config c) {
        sleep(pick(1200, 800, 500));
        List<String> both = new ArrayList<>();
        both.add("1");
        both.add("2");
        return runPages(c, both, null, true);
    }

    /** 점검 결과를 일괄조작이 알던 상태와 비교해 문장으로 만들고, 다른 기기를 표시한다. */
    private String finishCheck(Config c, java.util.Map<Integer, Integer> before, java.util.Map<Integer, Integer> actual) {
        List<Integer> diffs = new ArrayList<>();
        List<String> unread = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= c.count; i++) {
            Integer a = actual.get(i);
            if (a == null) {
                unread.add(String.format(java.util.Locale.US, "%02d", i));
                continue;
            }
            Integer e0 = before.get(i);
            int e = e0 == null ? 0 : e0;
            if (e != a) {
                diffs.add(i);
                sb.append(String.format(java.util.Locale.US, "%02d번  일괄조작: %s  →  세라젬 앱: %s\n", i, describeStatus(e), describeStatus(a)));
            }
        }
        Config.setDiffDevs(this, diffs);
        StringBuilder out = new StringBuilder();
        if (diffs.isEmpty()) out.append("점검 결과: 일괄조작과 세라젬 앱이 동일해요.");
        else out.append("점검 결과: ").append(diffs.size()).append("대가 달라요.\n").append(sb);
        if (!unread.isEmpty()) out.append("\n읽지 못한 기기: ").append(String.join(", ", unread));
        String text = out.toString().trim();
        MainActivity.pendingCheck = new String[]{diffs.isEmpty() && unread.isEmpty() ? "동일해요" : "다른 부분이 있어요", text};
        return text + "\n";
    }

    private static String describeStatus(int st) {
        if ((st & Config.ST_POWER) == 0 && (st & (Config.ST_SPINE | Config.ST_ABD)) == 0) return "꺼짐";
        StringBuilder b = new StringBuilder();
        if ((st & Config.ST_SPINE) != 0) b.append("척추 작동");
        if ((st & Config.ST_ABD) != 0) b.append(b.length() > 0 ? " + " : "").append("복부 작동");
        return b.length() == 0 ? "켜짐(작동 없음)" : b.toString();
    }

    private long lastNetAlert = 0;
    private int netAlerts = 0;

    /** '네트워크 알림 — 네트워크 상태를 확인해주세요' 창이 떠 있으면 확인을 눌러 닫는다. 닫았으면 true. */
    private boolean dismissNetworkAlert() {
        long now = System.currentTimeMillis();
        if (now - lastNetAlert < 700) return false;          // 너무 자주 검사하지 않음
        AccessibilityNodeInfo msg = textContaining("네트워크 상태를 확인");
        if (msg == null) return false;
        lastNetAlert = now;
        netAlerts++;
        AccessibilityNodeInfo okBtn = visibleText("확인");
        log("⚠ 네트워크 알림 창 (" + netAlerts + "번째) — 확인 누름");
        if (okBtn != null) clickNode(okBtn);
        else performGlobalAction(GLOBAL_ACTION_BACK);
        sleep(pick(1500, 1200, 1000));   // 세라젬 앱이 다시 연결할 시간
        return true;
    }

    /** 화면에 보이는 글자 중 일부가 들어간 것. */
    private AccessibilityNodeInfo textContaining(String part) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(part);
        if (found == null) return null;
        for (AccessibilityNodeInfo n : found) if (n.isVisibleToUser()) return n;
        return null;
    }

    /**
     * '기기와 연결이 해제 되었습니다'(다른 폰이 연결 중이거나 통신 끊김) 또는 '네트워크 알림' 창 처리.
     * 연결 해제면 '연결하기'를 한 번 눌러 보고, 그래도 안 되면 기기 상태 실패로 돌려준다.
     */
    private Result checkDisconnected() {
        dismissNetworkAlert();
        if (textContaining("연결이 해제") == null) return null;
        log("기기 연결 해제 화면 — '연결하기' 시도");
        snapshot("기기 연결 해제");
        AccessibilityNodeInfo btn = visibleText("연결하기");
        if (btn != null) {
            clickNode(btn);
            AccessibilityNodeInfo back = waitFor(() -> textContaining("연결이 해제") == null
                    && (findById(ID_POWER_OFF) != null || findById(Config.DEFAULT_POWER_ID) != null)
                    ? getRootInActiveWindow() : null, 8000);
            if (back != null) {
                log("다시 연결됨");
                return null;
            }
        }
        return Result.fail("기기 연결 끊김 (다른 폰이 연결 중이거나 통신 문제)").expected();
    }

    /** 모드 넘기기를 맨 앞 페이지로 되돌리고 이름이 자리 잡을 때까지 기다린다. */
    private void goToFirstPage() {
        waitFor(() -> centeredTitle().isEmpty() ? null : getRootInActiveWindow(), 3000);
        for (int i = 0; i < 5 && !stopRequested; i++) {
            final String before = centeredTitle();
            if (!scrollModePager(false)) break;
            waitFor(() -> {
                String t = centeredTitle();
                return (!t.isEmpty() && !t.equals(before)) ? getRootInActiveWindow() : null;
            }, pick(1200, 900, 700));
        }
        waitFor(() -> centeredTitle().isEmpty() ? null : getRootInActiveWindow(), 2000);
    }

    /** 화면 가운데 모드 페이지의 이름(예: 릴렉스 모드). */
    private String centeredTitle() {
        AccessibilityNodeInfo t = findCenteredById(ID_MODE_TITLE);
        return t == null || t.getText() == null ? "" : t.getText().toString().trim();
    }

    /** ▶ 버튼이 속한 페이지의 [모드 이름, 상태 글자]. */
    private String[] pageTexts(AccessibilityNodeInfo play) {
        String title = "", status = "";
        AccessibilityNodeInfo page = play.getParent();
        if (page != null) {
            for (int i = 0; i < page.getChildCount(); i++) {
                AccessibilityNodeInfo ch = page.getChild(i);
                if (ch == null || ch.getText() == null) continue;
                String id = ch.getViewIdResourceName();
                if (ID_MODE_TITLE.equals(id)) title = ch.getText().toString();
                else if (ID_MODE_STATUS.equals(id)) status = ch.getText().toString();
            }
        }
        return new String[]{title, status};
    }

    /** 그 모드가 이미 돌아가는 중인지: 상태 글자가 있고 '마사지 대기'가 아니면 작동 중. */
    private boolean isModeActive(String mode) {
        String st = norm(modeStatus(mode));
        return !st.isEmpty() && !st.equals("마사지대기");
    }

    /** 특정 모드 페이지의 상태 글자(마사지 대기 / 스캔 대기 / 척추 스캔중 / 마사지 중). */
    private String modeStatus(String mode) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "";
        List<AccessibilityNodeInfo> titles = root.findAccessibilityNodeInfosByViewId(ID_MODE_TITLE);
        if (titles == null) return "";
        String want = norm(mode);
        String fallback = "";
        for (AccessibilityNodeInfo t : titles) {
            if (!norm(t.getText()).equals(want)) continue;
            AccessibilityNodeInfo page = t.getParent();
            if (page == null) continue;
            for (int i = 0; i < page.getChildCount(); i++) {
                AccessibilityNodeInfo ch = page.getChild(i);
                if (ch != null && ID_MODE_STATUS.equals(ch.getViewIdResourceName()) && ch.getText() != null) {
                    if (t.isVisibleToUser()) return ch.getText().toString();
                    if (fallback.isEmpty()) fallback = ch.getText().toString();
                }
            }
        }
        return fallback;
    }

    /** "척추 스캔중" 같은 진행 화면이 앞에 있으면 모드 넘기기 화면으로 되돌린다. */
    private void showModePages() {
        AccessibilityNodeInfo prog = findById(ID_PROGRESS);
        if (prog == null || !prog.isVisibleToUser()) return;
        AccessibilityNodeInfo pages = findById(ID_DETAIL_PAGES);
        if (pages == null) return;
        pages.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        waitFor(() -> {
            AccessibilityNodeInfo p = findById(ID_PROGRESS);
            return (p == null || !p.isVisibleToUser()) ? getRootInActiveWindow() : null;
        }, 2500);
        sleep(pick(800, 500, 300));
    }

    /**
     * 원하는 모드 이름이 적힌 페이지 안의 ▶ 버튼.
     * 모드 페이지마다 ▶가 하나씩 있으므로, 같은 페이지의 제목(page_widget_03)으로 고른다.
     */
    private AccessibilityNodeInfo findPlayForMode(Config c, String mode) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        String pid = (c.playId == null || c.playId.trim().isEmpty()) ? ID_PLAY : c.playId.trim();
        List<AccessibilityNodeInfo> plays = root.findAccessibilityNodeInfosByViewId(pid);
        if (plays == null || plays.isEmpty()) return null;
        String want = norm(mode);
        AccessibilityNodeInfo visibleMatch = null, anyMatch = null, onlyVisible = null;
        int visibleCount = 0;
        for (AccessibilityNodeInfo p : plays) {
            AccessibilityNodeInfo page = p.getParent();
            String title = "";
            if (page != null) {
                for (int i = 0; i < page.getChildCount(); i++) {
                    AccessibilityNodeInfo ch = page.getChild(i);
                    if (ch != null && ID_MODE_TITLE.equals(ch.getViewIdResourceName())) {
                        title = norm(ch.getText());
                        break;
                    }
                }
            }
            if (p.isVisibleToUser()) {
                visibleCount++;
                onlyVisible = p;
            }
            if (!want.isEmpty() && title.equals(want)) {
                if (p.isVisibleToUser() && visibleMatch == null) visibleMatch = p;
                if (anyMatch == null) anyMatch = p;
            }
        }
        if (visibleMatch != null) return visibleMatch;
        if (anyMatch != null) return anyMatch;
        if (want.isEmpty() || visibleCount == 1) return visibleCount == 1 ? onlyVisible : findCenteredById(pid);
        return null;
    }

    /** "마사지 중" 또는 "척추 스캔중입니다." 같은 진행 화면이 보이면 작동 중. */
    private boolean isMassageRunning() {
        if (visibleText("마사지 중") != null) return true;
        AccessibilityNodeInfo p = findById(ID_PROGRESS);
        return p != null && p.isVisibleToUser();
    }

    // ---------------------------------------------------------------- 세라젬 화면 조작

    private boolean launchTarget() {
        log("세라젬 앱 새로 열기 — 이유: " + relaunchReason);
        relaunchReason = "이유 미상";
        Intent i = getPackageManager().getLaunchIntentForPackage(Config.TARGET_PKG);
        if (i == null) {
            log("세라젬 IoT 앱이 설치되어 있지 않거나 열 수 없음");
            return false;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            return false;
        }
        sleep(1500);
        return true;
    }

    /** 목록이 뜰 때까지 기다린 뒤, 필요하면 스크롤하면서 기기 카드를 찾는다. */
    private AccessibilityNodeInfo findCard(Config c, String name, boolean fresh) {
        final String head = c.prefix.trim();
        AccessibilityNodeInfo any = waitFor(() -> {
            if (fresh) dismissPopups();
            return findTextStartingWith(head);
        }, fresh ? 25000 : 4000);
        if (any == null) return null;
        sleep(fresh ? 1500 : pick(600, 300, 100)); // 기기 상태 글자가 새로 고쳐질 시간

        for (int i = 0; i < 12 && !stopRequested; i++) {
            AccessibilityNodeInfo t = findExactText(name);
            if (t != null && t.isVisibleToUser()) {
                if (speed >= 2) return containerOf(t);
                sleep(400);
                AccessibilityNodeInfo again = findExactText(name);
                return containerOf(again != null ? again : t);
            }
            if (!scrollForward()) swipeListUp();
            sleep(pick(900, 700, 450));
        }
        AccessibilityNodeInfo t = findExactText(name);
        return t == null ? null : containerOf(t);
    }

    /** 기기 화면에서 뒤로 가서 목록이 다시 보이면 true. */
    private boolean returnToList(Config c) {
        dismissNetworkAlert();
        if (isListScreen(c)) return true;
        for (int i = 0; i < 3 && !stopRequested; i++) {
            if (dismissNetworkAlert() && isListScreen(c)) return true;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            // 세라젬 앱 밖(홈 화면 등)이면 더 누르지 않는다 — 다음 기기에서 앱을 새로 연다
            if (root == null || root.getPackageName() == null
                    || !Config.TARGET_PKG.contentEquals(root.getPackageName())) {
                log("세라젬 앱 밖으로 나가 있음 — 뒤로가기 중단");
                return false;
            }
            // 앱 안의 ← 버튼을 먼저 쓰고, 없으면 시스템 뒤로가기
            AccessibilityNodeInfo backBtn = findById(ID_DETAIL_BACK);
            if (backBtn != null && backBtn.isVisibleToUser() && backBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                // ok
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
            // 목록이 다시 그려지는 데 통신 때문에 시간이 걸릴 수 있어 넉넉히 기다린다
            AccessibilityNodeInfo list = waitFor(() -> isListScreen(c) ? getRootInActiveWindow() : null, 3500);
            if (list != null) return true;
        }
        return false;
    }

    /** 기기 목록 화면인지: 기기 이름은 보이고, 기기 화면 버튼들은 없음. */
    private boolean isListScreen(Config c) {
        if (findTextStartingWith(c.prefix.trim()) == null) return false;
        AccessibilityNodeInfo d = findById(ID_DETAIL_BACK);
        if (d != null && d.isVisibleToUser()) return false;
        d = findById(c.powerId);
        return d == null || !d.isVisibleToUser();
    }

    private void dismissPopups() {
        dismissNetworkAlert();
        String[] labels = {"오늘 하루 보지 않기", "다시 보지 않기", "닫기"};
        for (String l : labels) {
            AccessibilityNodeInfo n = findExactText(l);
            if (n != null) {
                clickNode(n);
                sleep(500);
            }
        }
    }

    private boolean selectMode(String mode) {
        // 1) 왼쪽으로 밀며(다음 모드) 찾기 → 2) 못 찾으면 반대 방향으로
        for (int dir = 0; dir < 2; dir++) {
            int tries = dir == 0 ? 6 : 12;
            for (int i = 0; i <= tries && !stopRequested; i++) {
                if (isModeCentered(mode)) return true;
                if (i == tries) break;
                if (!scrollModePager(dir == 0)) swipeCarousel(dir == 0);
                // 넘어가는 즉시 다음 단계로(고정 대기 대신 확인될 때까지만 기다림)
                sleep(150);
                waitFor(() -> isModeCentered(mode) ? getRootInActiveWindow() : null, pick(1300, 1000, 800));
            }
        }
        return isModeCentered(mode);
    }

    private boolean isModeCentered(String mode) {
        Rect screen = screenRect();
        if (screen == null) return false;
        String want = norm(mode);
        List<AccessibilityNodeInfo> cands = new ArrayList<>();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            List<AccessibilityNodeInfo> byId = root.findAccessibilityNodeInfosByViewId(ID_MODE_TITLE);
            if (byId != null) cands.addAll(byId);
        }
        cands.addAll(findAllExactText(mode));
        for (AccessibilityNodeInfo n : cands) {
            if (!norm(n.getText()).equals(want)) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (!n.isVisibleToUser() || r.width() <= 0 || r.left < screen.left) continue;
            if (Math.abs(r.centerX() - screen.centerX()) < screen.width() * 0.18) return true;
        }
        // 좌표가 이상하게 나오는 경우: 화면에 보이는 모드 제목이 원하는 것 하나뿐이면 맞는 것으로 본다
        int visible = 0;
        boolean match = false;
        for (AccessibilityNodeInfo n : cands) {
            if (!ID_MODE_TITLE.equals(n.getViewIdResourceName()) || !n.isVisibleToUser()) continue;
            visible++;
            if (norm(n.getText()).equals(want)) match = true;
        }
        return visible == 1 && match;
    }

    /** 모드 넘기기 화면을 접근성 스크롤로 한 칸 넘긴다. */
    private boolean scrollModePager(boolean toNext) {
        AccessibilityNodeInfo pager = findById(ID_MODE_PAGES);
        if (pager == null || !pager.isScrollable()) return false;
        return pager.performAction(toNext ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    }

    /** 같은 ID가 여러 장(모드 페이지마다)에 있을 때 화면 가운데에 있는 것. */
    private AccessibilityNodeInfo findCenteredById(String id) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        Rect screen = screenRect();
        if (root == null || screen == null) return null;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
        if (found == null) return null;
        AccessibilityNodeInfo best = null;
        int bestD = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo n : found) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (!n.isVisibleToUser() || r.width() <= 0 || r.left < screen.left) continue;
            int d = Math.abs(r.centerX() - screen.centerX());
            if (d < bestD) {
                bestD = d;
                best = n;
            }
        }
        if (best == null) {
            // 좌표가 이상하게 나오는 화면: 보이는 것이 딱 하나면 그것을 쓴다
            AccessibilityNodeInfo only = null;
            int cnt = 0;
            for (AccessibilityNodeInfo n : found) {
                if (n.isVisibleToUser()) {
                    cnt++;
                    only = n;
                }
            }
            if (cnt == 1) best = only;
        }
        return best;
    }

    private void swipeCarousel(boolean toNext) {
        Rect screen = screenRect();
        if (screen == null) return;
        int y = (int) (screen.top + screen.height() * 0.3);
        AccessibilityNodeInfo anchor = statusAnchor();
        if (anchor != null) {
            Rect r = new Rect();
            anchor.getBoundsInScreen(r);
            y = r.centerY();
        }
        int left = (int) (screen.left + screen.width() * 0.18);
        int right = (int) (screen.left + screen.width() * 0.82);
        if (toNext) swipe(right, y, left, y, 300);
        else swipe(left, y, right, y, 300);
    }

    /** 전원 끄기 확인 창이 뜨면 확인 쪽 버튼을 누른다. */
    private void confirmDialogIfAny() {
        String[] labels = {"전원 끄기", "끄기", "확인", "예", "네"};
        long end = System.currentTimeMillis() + pick(2000, 1500, 1200);
        while (System.currentTimeMillis() < end && !stopRequested) {
            for (String l : labels) {
                AccessibilityNodeInfo n = visibleText(l);
                if (n != null) {
                    clickNode(n);
                    sleep(300);
                    return;
                }
            }
            sleep(150);
        }
    }

    /** 원 안의 "마사지 대기" 글자. ▶ 버튼 위치와 모드 넘기기 높이의 기준점. */
    private AccessibilityNodeInfo statusAnchor() {
        AccessibilityNodeInfo n = findExactText("마사지 대기");
        if (n == null) n = findExactText("마사지 중");
        return n;
    }

    /**
     * 켜진 기기 화면 오른쪽 위의 붉은 테두리 전원 버튼.
     * 1) 이름·설명에 power/전원이 들어간 버튼 → 2) 오른쪽 위 영역의 작은 버튼 중 가장 아래쪽(⋯ 메뉴 아래).
     */
    private AccessibilityNodeInfo findOffButton() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        Rect screen = screenRect();
        if (root == null || screen == null) return null;
        AccessibilityNodeInfo known = findById(ID_POWER_OFF);
        if (known != null) return known;
        List<AccessibilityNodeInfo> nodes = allNodes(root);
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser()) continue;
            String id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName().toLowerCase(Locale.US);
            String desc = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
            if (id.contains("power") || desc.contains("전원") || desc.toLowerCase(Locale.US).contains("power")) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (r.centerY() < screen.top + screen.height() * 0.3) return n;
            }
        }
        AccessibilityNodeInfo best = null;
        int bestTop = -1;
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isClickable() || !n.isVisibleToUser()) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() > screen.width() * 0.22 || r.height() > screen.height() * 0.12) continue;
            if (r.centerX() < screen.left + screen.width() * 0.78) continue;
            if (r.centerY() < screen.top + screen.height() * 0.035 || r.centerY() > screen.top + screen.height() * 0.25) continue;
            String desc = (n.getContentDescription() == null ? "" : n.getContentDescription().toString())
                    + (n.getText() == null ? "" : n.getText().toString());
            if (desc.contains("더보기") || desc.contains("메뉴") || desc.contains("⋯") || desc.contains("...")) continue;
            if (r.top > bestTop) {
                bestTop = r.top;
                best = n;
            }
        }
        return best;
    }
    // ---------------------------------------------------------------- 문제 리포트

    private void log(String msg) {
        String t = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        synchronized (diag) {
            if (diag.length() < 400000) diag.append(t).append("  ").append(msg).append('\n');
        }
    }

    /** 지금 화면에서 보이는 요소(ID·글자가 있는 것)만 간단히 기록. */
    private void snapshot(String reason) {
        StringBuilder sb = new StringBuilder();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        sb.append("    ┌ 화면 기록: ").append(reason);
        if (root == null) {
            sb.append(" — 화면을 읽지 못함\n");
        } else {
            sb.append(" — 앱 ").append(root.getPackageName()).append('\n');
            int lines = 0;
            for (AccessibilityNodeInfo n : allNodes(root)) {
                if (!n.isVisibleToUser()) continue;
                String id = n.getViewIdResourceName();
                CharSequence tx = n.getText(), ds = n.getContentDescription();
                if (id == null && tx == null && ds == null) continue;
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                sb.append("    │ ");
                if (id != null) sb.append(id.substring(id.indexOf('/') + 1));
                if (tx != null) sb.append(" \"").append(clean(tx.toString())).append('"');
                if (ds != null) sb.append(" desc=\"").append(clean(ds.toString())).append('"');
                if (n.isClickable()) sb.append(" [C]");
                sb.append(' ').append(b.toShortString()).append('\n');
                if (++lines >= 120) {
                    sb.append("    │ …(이하 생략)\n");
                    break;
                }
            }
        }
        sb.append("    └\n");
        synchronized (diag) {
            if (diag.length() < 400000) diag.append(sb);
        }
    }

    /** 문제가 있었던 실행만 요약해서 실패 기록 모음에 쌓는다(화면 기록은 빼고 핵심 줄만). */
    private void recordFailHistory(int mode, String when, long secs, String resultLines) {
        String ver = "?";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        StringBuilder b = new StringBuilder();
        b.append("[").append(when).append("] 전체 ").append(modeLabel(mode)).append(" · ").append(secs)
                .append("초 · v").append(ver).append('\n');
        for (String line : resultLines.split("\n")) {
            if (line.startsWith("❌ ")) b.append(line).append('\n');
            else if (line.contains("확인 안 됨")) b.append("❔ ").append(line.substring(line.indexOf(' ') + 1)).append('\n');
            else if (line.startsWith("⏹")) b.append(line).append('\n');
        }
        if (netAlerts > 0) b.append("네트워크 알림 ").append(netAlerts).append("번\n");
        String d;
        synchronized (diag) {
            d = diag.toString();
        }
        StringBuilder key = new StringBuilder();
        String device = "";
        for (String line : d.split("\n")) {
            if (line.startsWith("    ")) continue;                 // 화면 기록은 뺀다
            int sp = line.indexOf("  ");
            String body = sp > 0 ? line.substring(sp + 2) : line;
            if (body.startsWith("── ")) {
                device = body.substring(3);
                continue;
            }
            if (body.contains("❌") || body.contains("⚠") || body.contains("확인 안 됨") || body.contains("다시 누름")
                    || body.contains("한 번 더") || body.contains("연결") || body.contains("못 ")
                    || (body.startsWith("세라젬 앱 새로 열기") && !body.contains("처음 시작"))) {
                key.append("  · ").append(line.substring(0, Math.max(0, sp))).append(' ')
                        .append(device.isEmpty() ? "" : device + ": ").append(body).append('\n');
            }
        }
        if (key.length() > 0) b.append("상세:\n").append(key);
        FailHistory.append(this, b.toString().trim());
    }

    private String saveIssueReport(String summary) {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String text = buildReport(summary);
        MainActivity.lastIssueReport = text;
        return saveToDownloads("ceragem_report_" + ts + ".txt", text);
    }

    private String buildReport(String summary) {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String ver = "?";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        String ceragemVer = "?";
        try {
            ceragemVer = getPackageManager().getPackageInfo(Config.TARGET_PKG, 0).versionName;
        } catch (Exception ignored) {
        }
        StringBuilder r = new StringBuilder();
        r.append(issues > 0 ? "# 세라젬 일괄조작 문제 리포트 " : "# 세라젬 일괄조작 진행 기록 ").append(ts).append('\n')
                .append("일괄조작 앱 v").append(ver).append(" · 세라젬 IoT v").append(ceragemVer)
                .append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" · Android ").append(Build.VERSION.RELEASE).append("\n\n")
                .append("## 결과\n").append(summary).append("\n## 진행 기록\n");
        synchronized (diag) {
            r.append(diag);
        }
        return r.toString();
    }

    // ---------------------------------------------------------------- 화면 분석(덤프)

    public void dumpLater(int seconds) {
        toast(seconds + "초 뒤에 화면을 분석해요. 세라젬 앱 화면을 띄워 두세요.");
        main.postDelayed(() -> new Thread(this::doDump, "dump").start(), seconds * 1000L);
    }

    private void doDump() {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        StringBuilder sb = new StringBuilder();
        sb.append("# 세라젬 화면 분석 ").append(ts).append('\n');
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            sb.append("(화면을 읽지 못했어요)\n");
        } else {
            sb.append("package=").append(root.getPackageName()).append('\n');
            sb.append("표시: C=누를 수 있음 S=스크롤 V=화면에 보임\n\n");
            dumpNode(root, 0, sb);
        }
        String text = sb.toString();
        String saved = saveToDownloads("ceragem_dump_" + ts + ".txt", text);
        toast(saved != null ? "분석 완료: " + saved : "분석 완료");
        showInApp(null, text);
    }

    private void dumpNode(AccessibilityNodeInfo n, int depth, StringBuilder sb) {
        if (n == null || depth > 40) return;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        for (int i = 0; i < depth; i++) sb.append("  ");
        CharSequence cls = n.getClassName();
        String c = cls == null ? "?" : cls.toString();
        int dot = c.lastIndexOf('.');
        sb.append(dot >= 0 ? c.substring(dot + 1) : c);
        if (n.getViewIdResourceName() != null) sb.append(" id=").append(n.getViewIdResourceName());
        if (n.getText() != null) sb.append(" text=\"").append(clean(n.getText().toString())).append('"');
        if (n.getContentDescription() != null) sb.append(" desc=\"").append(clean(n.getContentDescription().toString())).append('"');
        StringBuilder flags = new StringBuilder();
        if (n.isClickable()) flags.append('C');
        if (n.isScrollable()) flags.append('S');
        if (n.isVisibleToUser()) flags.append('V');
        if (flags.length() > 0) sb.append(" [").append(flags).append(']');
        sb.append(' ').append(b.toShortString()).append('\n');
        for (int i = 0; i < n.getChildCount(); i++) dumpNode(n.getChild(i), depth + 1, sb);
    }

    private String saveToDownloads(String fileName, String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return null;
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) return null;
                    os.write(data);
                }
                return "Download/" + fileName;
            } else {
                File dir = getExternalFilesDir(null);
                if (dir == null) return null;
                File f = new File(dir, fileName);
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(data);
                }
                return f.getAbsolutePath();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 노드 도우미

    private AccessibilityNodeInfo waitFor(Finder f, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        int extended = 0;
        while (System.currentTimeMillis() < end && !stopRequested) {
            // 세라젬 앱 통신이 불안정할 때 언제든 뜨는 '네트워크 알림' 창은 바로 닫고 계속 기다린다(연장은 3번까지)
            if (dismissNetworkAlert() && extended < 3) {
                end += 3000;
                extended++;
            }
            AccessibilityNodeInfo n = null;
            try {
                n = f.find();
            } catch (Exception ignored) {
            }
            if (n != null) return n;
            sleep(speed >= 2 ? 120 : 300);
        }
        return null;
    }

    private static String norm(CharSequence s) {
        return s == null ? "" : s.toString().replaceAll("\\s+", "");
    }

    private static String clean(String s) {
        return s.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private List<AccessibilityNodeInfo> findAllExactText(String text) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return out;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(text);
        if (found == null) return out;
        String want = norm(text);
        for (AccessibilityNodeInfo n : found) {
            if (norm(n.getText()).equals(want) || norm(n.getContentDescription()).equals(want)) out.add(n);
        }
        return out;
    }

    private AccessibilityNodeInfo findExactText(String text) {
        AccessibilityNodeInfo fallback = null;
        for (AccessibilityNodeInfo n : findAllExactText(text)) {
            if (n.isVisibleToUser()) return n;
            if (fallback == null) fallback = n;
        }
        return fallback;
    }

    /** 화면에 실제로 보이는 경우에만 돌려준다. */
    private AccessibilityNodeInfo visibleText(String text) {
        for (AccessibilityNodeInfo n : findAllExactText(text)) if (n.isVisibleToUser()) return n;
        return null;
    }

    private AccessibilityNodeInfo findTextStartingWith(String head) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(head);
        if (found == null) return null;
        String want = norm(head);
        for (AccessibilityNodeInfo n : found) {
            if (norm(n.getText()).startsWith(want)) return n;
        }
        return null;
    }

    private AccessibilityNodeInfo findById(String id) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || id == null || id.isEmpty()) return null;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
        if (found == null || found.isEmpty()) return null;
        for (AccessibilityNodeInfo n : found) if (n.isVisibleToUser()) return n;
        return found.get(0);
    }

    private List<AccessibilityNodeInfo> allNodes(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty() && out.size() < 3000) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            out.add(n);
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo ch = n.getChild(i);
                if (ch != null) q.add(ch);
            }
        }
        return out;
    }

    private AccessibilityNodeInfo containerOf(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        AccessibilityNodeInfo p = n.getParent();
        return p != null ? p : n;
    }

    private String collectText(AccessibilityNodeInfo n) {
        StringBuilder sb = new StringBuilder();
        for (AccessibilityNodeInfo x : allNodes(n)) {
            if (x.getText() != null) sb.append(x.getText()).append(' ');
        }
        return sb.toString();
    }

    private boolean clickNode(AccessibilityNodeInfo n) {
        if (n == null) return false;
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (cur.isClickable()) {
                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                break;
            }
            cur = cur.getParent();
        }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        return tap(r.centerX(), r.centerY());
    }

    private boolean scrollForward() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo best = null;
        long bestArea = 0;
        for (AccessibilityNodeInfo n : allNodes(root)) {
            if (!n.isScrollable()) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            long area = (long) r.width() * r.height();
            if (area > bestArea) {
                bestArea = area;
                best = n;
            }
        }
        return best != null && best.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }

    private void swipeListUp() {
        Rect s = screenRect();
        if (s == null) return;
        int x = s.centerX();
        swipe(x, (int) (s.top + s.height() * 0.78), x, (int) (s.top + s.height() * 0.3), 350);
    }

    private Rect screenRect() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        Rect r = new Rect();
        root.getBoundsInScreen(r);
        return r.isEmpty() ? null : r;
    }

    // ---------------------------------------------------------------- 제스처

    private boolean tap(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        return gesture(new GestureDescription.StrokeDescription(p, 0, 60));
    }

    private boolean swipe(int x1, int y1, int x2, int y2, long ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return gesture(new GestureDescription.StrokeDescription(p, 0, ms));
    }

    private boolean gesture(GestureDescription.StrokeDescription stroke) {
        GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        boolean dispatched = dispatchGesture(g, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                ok[0] = true;
                latch.countDown();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                latch.countDown();
            }
        }, null);
        if (!dispatched) return false;
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return ok[0];
    }

    // ---------------------------------------------------------------- 기타

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void toast(final String msg) {
        main.post(() -> {
            if (lastToast != null) lastToast.cancel();
            lastToast = Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT);
            lastToast.show();
        });
    }

    private void showInApp(String report, String dump) {
        if (report != null) MainActivity.lastReport = report;
        if (dump != null) MainActivity.lastDump = dump;
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra(MainActivity.EXTRA_SCROLL_BOTTOM, true);
        try {
            startActivity(i);
        } catch (Exception ignored) {
        }
    }
}
