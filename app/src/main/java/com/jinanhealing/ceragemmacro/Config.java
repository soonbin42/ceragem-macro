package com.jinanhealing.ceragemmacro;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** 사용자가 바꿀 수 있는 설정값. */
public class Config {
    public static final String TARGET_PKG = "kr.co.ceragem.iot";
    public static final String DEFAULT_POWER_ID = "kr.co.ceragem.iot:id/do_control_action";

    public int count = 10;
    public String prefix = "MASTER V9";
    public String modeName = "1, 2";
    public String playId = "";
    public String changeModeName = "릴렉스 모드";
    public String changeModeNo = "2";
    public String powerId = DEFAULT_POWER_ID;
    /** 0 안정 · 1 보통 · 2 빠름 */
    public int speed = 2;
    public Set<Integer> selected = new TreeSet<>();

    public static Config load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
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
        c.playId = p.getString("playId", "");
        c.changeModeName = p.getString("changeModeName", c.changeModeName);
        c.changeModeNo = p.getString("changeModeNo", c.changeModeNo);
        c.powerId = p.getString("powerId", DEFAULT_POWER_ID);
        c.speed = Math.max(0, Math.min(2, p.getInt("speed", 2)));
        String sel = p.getString("selected", null);
        c.selected = new TreeSet<>();
        if (sel == null) {
            for (int i = 1; i <= c.count; i++) c.selected.add(i);
        } else if (!sel.isEmpty()) {
            for (String s : sel.split(",")) {
                try {
                    c.selected.add(Integer.parseInt(s.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return c;
    }

    public void save(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (Integer i : selected) {
            if (sb.length() > 0) sb.append(',');
            sb.append(i);
        }
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                .putInt("count", count)
                .putString("prefix", prefix)
                .putString("modeName", modeName)
                .putString("playId", playId)
                .putString("changeModeName", changeModeName)
                .putString("changeModeNo", changeModeNo)
                .putString("powerId", powerId)
                .putInt("speed", speed)
                .putInt("cfgVer", 3)
                .putString("selected", sb.toString())
                .apply();
    }

    public List<Integer> selectedList() {
        List<Integer> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) if (selected.contains(i)) out.add(i);
        return out;
    }

    public String deviceName(int i) {
        return prefix.trim() + " " + String.format(Locale.US, "%02d", i);
    }
}
