package com.jinanhealing.ceragemmacro;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** 사용자가 바꿀 수 있는 설정값과, 기기별 선택·상태. */
public class Config {
    public static final String TARGET_PKG = "kr.co.ceragem.iot";
    public static final String DEFAULT_POWER_ID = "kr.co.ceragem.iot:id/do_control_action";

    /** 기기 상태 비트: 전원 켜짐 / 척추(1번 페이지) 작동 / 복부(2번 페이지, 음파 진동) 작동 */
    public static final int ST_POWER = 1;
    public static final int ST_SPINE = 2;
    public static final int ST_ABD = 4;
    public static final int ST_ALL = 7;

    public int count = 10;
    public String prefix = "MASTER V9";
    public String modeName = "1, 2";
    public String playId = "";
    public String changeModeName = "릴렉스 모드";
    public String changeModeNo = "2";
    public String changeSpeed = "고속";
    public String powerId = DEFAULT_POWER_ID;
    /** 0 안정 · 1 보통 · 2 빠름 */
    public int speed = 2;

    /** 기기별 선택: 척추 / 복부 */
    public Set<Integer> selSpine = new TreeSet<>();
    public Set<Integer> selAbd = new TreeSet<>();
    /** 마지막으로 실행·적용한 선택 (이것과 달라진 기기가 '수정됨') */
    public Set<Integer> baseSpine = new TreeSet<>();
    public Set<Integer> baseAbd = new TreeSet<>();
    /** 기기별 상태(ST_* 비트). 일괄조작이 알고 있는 값이며 점검으로 실제와 맞춘다. */
    public Map<Integer, Integer> status = new TreeMap<>();
    /** 점검에서 세라젬 앱과 달랐던 기기(빨간 테두리) */
    public Set<Integer> diffDevs = new TreeSet<>();
    /** '실행'을 누른 시각(ms). 0이면 없음 */
    public long runStartedAt = 0;
    /** '모드 변경'으로 골라 둔 속도(실행을 누를 때 적용): -1 없음 · 0 저속 · 1 정속 · 2 고속 */
    public int lastSpeedIdx = -1;
    /** 모드 변경을 눌러 골라 둔 속도를 다음 실행에 한 번만 적용할지 */
    public boolean speedPending = false;
    /** 기기별로 마지막에 적용한 속도(0~2). 없으면 아직 모름 → 골라 둔 속도와 다를 때만 바꾼다. */
    public Map<Integer, Integer> devSpeed = new TreeMap<>();

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    public static Config load(Context ctx) {
        SharedPreferences p = prefs(ctx);
        Config c = new Config();
        c.count = Math.max(1, Math.min(30, p.getInt("count", 10)));
        c.prefix = p.getString("prefix", c.prefix);
        c.modeName = p.getString("modeName", c.modeName);
        // v1.0.8: 실행은 릴렉스 모드 + 음파 진동 마사지1 두 가지를 차례로 켠다
        // v1.0.13: 모드 이름 대신 페이지 순서(1=척추, 2=음파 진동)로 실행
        if (p.getInt("cfgVer", 1) < 3) {
            String m = c.modeName.replaceAll("\\s+", "");
            if (m.equals("음파진동마사지1") || m.equals("릴렉스모드,음파진동마사지1")) c.modeName = "1, 2";
        }
        if (c.modeName == null || c.modeName.trim().isEmpty()) c.modeName = "1, 2";
        c.playId = p.getString("playId", "");
        c.changeModeName = p.getString("changeModeName", c.changeModeName);
        c.changeModeNo = p.getString("changeModeNo", c.changeModeNo);
        c.changeSpeed = p.getString("changeSpeed", c.changeSpeed);
        // v1.0.18: 모드 변경 후 속도 기본값을 고속으로
        if (p.getInt("cfgVer", 1) < 4 && "저속".equals(c.changeSpeed.trim())) c.changeSpeed = "고속";
        c.powerId = p.getString("powerId", DEFAULT_POWER_ID);
        c.speed = Math.max(0, Math.min(2, p.getInt("speed", 2)));

        // 선택: 예전 버전은 기기 단위 'selected' 하나뿐 → 척추·복부 둘 다로 옮긴다
        String legacy = p.getString("selected", null);
        c.selSpine = parseSet(p.getString("selSpine", legacy), c.count);
        c.selAbd = parseSet(p.getString("selAbd", legacy), c.count);
        c.baseSpine = p.contains("baseSpine") ? parseSet(p.getString("baseSpine", ""), c.count) : new TreeSet<>(c.selSpine);
        c.baseAbd = p.contains("baseAbd") ? parseSet(p.getString("baseAbd", ""), c.count) : new TreeSet<>(c.selAbd);
        c.diffDevs = parseSet(p.getString("diffDevs", ""), 99);
        c.status = parseStatus(p.getString("status", ""));
        c.runStartedAt = p.getLong("runStartedAt", 0);
        c.lastSpeedIdx = p.getInt("lastSpeedIdx", -1);
        c.speedPending = p.getBoolean("speedPending", false);
        c.devSpeed = parseStatus(p.getString("devSpeed", ""));
        return c;
    }

    /** 설정값과 선택을 저장한다. (상태·기준 선택·경과 시간은 건드리지 않는다) */
    public void save(Context ctx) {
        prefs(ctx).edit()
                .putInt("count", count)
                .putString("prefix", prefix)
                .putString("modeName", modeName)
                .putString("playId", playId)
                .putString("changeModeName", changeModeName)
                .putString("changeModeNo", changeModeNo)
                .putString("changeSpeed", changeSpeed)
                .putString("powerId", powerId)
                .putInt("speed", speed)
                .putInt("cfgVer", 5)
                .putString("selSpine", joinSet(selSpine))
                .putString("selAbd", joinSet(selAbd))
                .putString("diffDevs", joinSet(diffDevs))
                .apply();
    }

    // ------------------------------------------------------------ 선택·수정

    /** 척추나 복부가 하나라도 선택된 기기 번호들. */
    public List<Integer> selectedList() {
        List<Integer> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) if (selSpine.contains(i) || selAbd.contains(i)) out.add(i);
        return out;
    }

    /** 기기 하나에서 실행할 페이지 번호(문자열): 척추 = 1, 복부(음파 진동) = 2. */
    public List<String> pagesFor(int dev) {
        List<String> out = new ArrayList<>();
        if (selSpine.contains(dev)) out.add("1");
        if (selAbd.contains(dev)) out.add("2");
        return out;
    }

    /** 마지막 실행·적용 이후 선택이 바뀐 기기인지. */
    public boolean modified(int dev) {
        boolean sp = selSpine.contains(dev), ab = selAbd.contains(dev);
        // 선택이 없고 전원도 꺼져 있다고 알고 있으면 할 일이 없다 (옛 기준이 남아 있어도 수정으로 보지 않음)
        if (!sp && !ab && (statusOf(dev) & ST_POWER) == 0) return false;
        return sp != baseSpine.contains(dev) || ab != baseAbd.contains(dev);
    }

    /** 실제로 돌고 있다고 볼 수 있는 기준: 전원이 꺼져 있다고 알면 아무것도 돌지 않는 것. */
    public boolean effBaseSpine(int dev) {
        return (statusOf(dev) & ST_POWER) != 0 && baseSpine.contains(dev);
    }

    public boolean effBaseAbd(int dev) {
        return (statusOf(dev) & ST_POWER) != 0 && baseAbd.contains(dev);
    }

    public int modifiedCount() {
        int n = 0;
        for (int i = 1; i <= count; i++) if (modified(i)) n++;
        return n;
    }

    public int statusOf(int dev) {
        Integer v = status.get(dev);
        return v == null ? 0 : v;
    }

    /** 이 기기에 마지막으로 적용한 속도. 모르면 -1. */
    public int devSpeedOf(int dev) {
        Integer v = devSpeed.get(dev);
        return v == null ? -1 : v;
    }

    /** '수정 사항 적용'이 이 기기에 할 일을 짧은 글로. (MacroService.doApply와 같은 규칙) */
    public String planText(int dev) {
        boolean sp = selSpine.contains(dev), ab = selAbd.contains(dev);
        boolean bsp = effBaseSpine(dev), bab = effBaseAbd(dev);
        if (!sp && !ab) return "전원 끄기";
        if (bsp && !sp) return "전원 끄기 → 켜기 → 복부만 실행";
        List<String> parts = new ArrayList<>();
        if (bab && !ab) parts.add("복부 멈춤");
        List<String> run = new ArrayList<>();
        if (sp && !bsp) run.add("척추");
        if (ab && !bab) run.add("복부");
        if (!run.isEmpty()) parts.add(String.join("·", run) + " 실행");
        return parts.isEmpty() ? "변경 없음" : String.join(" + ", parts);
    }

    public String deviceName(int i) {
        return prefix.trim() + " " + String.format(Locale.US, "%02d", i);
    }

    // ------------------------------------------------------------ 부분 저장 (서비스가 실행 중에 호출)

    /** 기기 상태 비트를 켜고(setBits) 끈다(clearBits). */
    public static synchronized void updateStatus(Context ctx, int dev, int setBits, int clearBits) {
        SharedPreferences p = prefs(ctx);
        Map<Integer, Integer> m = parseStatus(p.getString("status", ""));
        Integer cur = m.get(dev);
        int v = cur == null ? 0 : cur;
        v = (v & ~clearBits) | setBits;
        m.put(dev, v);
        p.edit().putString("status", joinStatus(m)).apply();
    }

    public static synchronized void setDiffDevs(Context ctx, Collection<Integer> devs) {
        SharedPreferences.Editor e = prefs(ctx).edit().putString("diffDevs", joinSet(devs));
        if (devs != null && !devs.isEmpty()) e.putLong("diffAt", System.currentTimeMillis());
        e.apply();
    }

    public static synchronized void setRunStarted(Context ctx, long t) {
        prefs(ctx).edit().putLong("runStartedAt", t).apply();
    }

    public static synchronized void setSpeedPending(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("speedPending", on).apply();
    }

    /** 점검에서 '다르다'고 표시한 빨간 테두리는 30분이 지나면 지운다. */
    public static synchronized void expireDiff(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (p.getString("diffDevs", "").isEmpty()) return;
        long at = p.getLong("diffAt", 0);
        if (at == 0 || System.currentTimeMillis() - at > 30 * 60 * 1000L) {
            p.edit().putString("diffDevs", "").apply();
        }
    }

    public static synchronized void setLastSpeed(Context ctx, int idx) {
        prefs(ctx).edit().putInt("lastSpeedIdx", idx).apply();
    }

    /** 기기에 적용한 속도를 기억한다. idx < 0 이면 지운다(전원을 끄면 속도도 처음으로 돌아감). */
    public static synchronized void setDevSpeed(Context ctx, int dev, int idx) {
        SharedPreferences p = prefs(ctx);
        Map<Integer, Integer> m = parseStatus(p.getString("devSpeed", ""));
        if (idx < 0) m.remove(dev);
        else m.put(dev, idx);
        p.edit().putString("devSpeed", joinStatus(m)).apply();
    }

    /** 실행한 기기들의 '기준 선택'에 지금 선택을 더한다. (실행은 해제된 칸을 멈추지 않으므로 빼지 않음) */
    /** 실행: 선택한 부위를 기준에 더한다. 실행 전에 꺼져 있다고 알던 기기는 아무것도 안 돌던 것이므로 선택대로 바꾼다. */
    public static synchronized void baselineAdd(Context ctx, Config sel, Collection<Integer> devs, Map<Integer, Integer> before) {
        SharedPreferences p = prefs(ctx);
        Set<Integer> bs = parseSet(p.getString("baseSpine", joinSet(sel.baseSpine)), 99);
        Set<Integer> ba = parseSet(p.getString("baseAbd", joinSet(sel.baseAbd)), 99);
        for (int d : devs) {
            Integer b0 = before == null ? null : before.get(d);
            boolean wasOn = b0 != null && (b0 & ST_POWER) != 0;
            if (!wasOn) {
                bs.remove(d);
                ba.remove(d);
            }
            if (sel.selSpine.contains(d)) bs.add(d);
            if (sel.selAbd.contains(d)) ba.add(d);
        }
        p.edit().putString("baseSpine", joinSet(bs)).putString("baseAbd", joinSet(ba)).apply();
    }

    /** 적용한 기기들의 '기준 선택'을 지금 선택과 똑같이 맞춘다. */
    public static synchronized void baselineSet(Context ctx, Config sel, Collection<Integer> devs) {
        SharedPreferences p = prefs(ctx);
        Set<Integer> bs = parseSet(p.getString("baseSpine", joinSet(sel.baseSpine)), 99);
        Set<Integer> ba = parseSet(p.getString("baseAbd", joinSet(sel.baseAbd)), 99);
        for (int d : devs) {
            if (sel.selSpine.contains(d)) bs.add(d);
            else bs.remove(d);
            if (sel.selAbd.contains(d)) ba.add(d);
            else ba.remove(d);
        }
        p.edit().putString("baseSpine", joinSet(bs)).putString("baseAbd", joinSet(ba)).apply();
    }

    /** 점검에서 다르게 나온 기기들의 '기준'을 세라젬 앱의 실제 작동 상태로 맞춘다. */
    public static synchronized void baselineFromActual(Context ctx, Config sel, Map<Integer, Integer> actual, Collection<Integer> devs) {
        SharedPreferences p = prefs(ctx);
        Set<Integer> bs = parseSet(p.getString("baseSpine", joinSet(sel.baseSpine)), 99);
        Set<Integer> ba = parseSet(p.getString("baseAbd", joinSet(sel.baseAbd)), 99);
        for (int d : devs) {
            Integer a = actual.get(d);
            if (a == null) continue;
            if ((a & ST_SPINE) != 0) bs.add(d);
            else bs.remove(d);
            if ((a & ST_ABD) != 0) ba.add(d);
            else ba.remove(d);
        }
        p.edit().putString("baseSpine", joinSet(bs)).putString("baseAbd", joinSet(ba)).apply();
    }

    /** 끈 기기들은 기준 선택에서 뺀다. */
    public static synchronized void baselineClear(Context ctx, Config sel, Collection<Integer> devs) {
        SharedPreferences p = prefs(ctx);
        Set<Integer> bs = parseSet(p.getString("baseSpine", joinSet(sel.baseSpine)), 99);
        Set<Integer> ba = parseSet(p.getString("baseAbd", joinSet(sel.baseAbd)), 99);
        for (int d : devs) {
            bs.remove(d);
            ba.remove(d);
        }
        p.edit().putString("baseSpine", joinSet(bs)).putString("baseAbd", joinSet(ba)).apply();
    }

    // ------------------------------------------------------------ 문자열 변환

    private static Set<Integer> parseSet(String s, int max) {
        Set<Integer> out = new TreeSet<>();
        if (s == null) {
            for (int i = 1; i <= max && max < 99; i++) out.add(i);   // 저장된 값이 없으면 전부 선택
            return out;
        }
        if (s.isEmpty()) return out;
        for (String t : s.split(",")) {
            try {
                out.add(Integer.parseInt(t.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static String joinSet(Collection<Integer> set) {
        StringBuilder sb = new StringBuilder();
        for (Integer i : set) {
            if (sb.length() > 0) sb.append(',');
            sb.append(i);
        }
        return sb.toString();
    }

    private static Map<Integer, Integer> parseStatus(String s) {
        Map<Integer, Integer> m = new TreeMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String t : s.split(",")) {
            String[] kv = t.split(":");
            if (kv.length != 2) continue;
            try {
                m.put(Integer.parseInt(kv[0].trim()), Integer.parseInt(kv[1].trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return m;
    }

    private static String joinStatus(Map<Integer, Integer> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : m.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }
}
