package app.acremote;

import android.content.SharedPreferences;

/** Everything the remote "remembers", like the LCD on the physical remote. */
public final class AcState {
    public static final int MODE_AUTO = 0, MODE_COOL = 1, MODE_DRY = 2, MODE_FAN = 3, MODE_HEAT = 4;
    public static final String[] MODE_NAMES = {"Auto", "Cool", "Dry", "Fan", "Heat"};

    public static final int FAN_AUTO = 0, FAN_LOW = 1, FAN_MED = 2, FAN_HIGH = 3;
    public static final String[] FAN_NAMES = {"Auto", "Low", "Medium", "High"};

    public static final int TEMP_MIN = 16, TEMP_MAX = 30;

    /** Signal family. 0 = Type A (TCL-style), 1 = Type B (Midea/Coolix-style), 2 = Type C (TCL variant). */
    public int type = 0;

    public boolean power = false;
    public int mode = MODE_COOL;
    public int temp = 24;
    public int fan = FAN_AUTO;
    public boolean swingV = false;
    public boolean swingH = false;
    public boolean turbo = false;
    public boolean sleep = false;
    public boolean light = true;      // AC display LED on/off
    public int timerMins = 0;         // 0 = no timer
    public long timerSetAt = 0;       // wall-clock millis when timer was sent
    public int clockOffsetMins = 0;   // user adjustment on top of phone time

    /** Signal bits found with the Button Finder (byte*8 + bit), -1 = use the standard one. */
    public int ledBit = -1, turboBit = -1, sleepBit = -1, cleanBit = -1;

    public void load(SharedPreferences p) {
        type = p.getInt("type", type);
        power = p.getBoolean("power", power);
        mode = p.getInt("mode", mode);
        temp = p.getInt("temp", temp);
        fan = p.getInt("fan", fan);
        swingV = p.getBoolean("swingV", swingV);
        swingH = p.getBoolean("swingH", swingH);
        turbo = p.getBoolean("turbo", turbo);
        sleep = p.getBoolean("sleep", sleep);
        light = p.getBoolean("light", light);
        timerMins = p.getInt("timerMins", timerMins);
        timerSetAt = p.getLong("timerSetAt", timerSetAt);
        clockOffsetMins = p.getInt("clockOffset", clockOffsetMins);
        ledBit = p.getInt("ledBit", ledBit);
        turboBit = p.getInt("turboBit", turboBit);
        sleepBit = p.getInt("sleepBit", sleepBit);
        cleanBit = p.getInt("cleanBit", cleanBit);
        clamp();
    }

    public void save(SharedPreferences p) {
        p.edit()
                .putInt("type", type)
                .putBoolean("power", power)
                .putInt("mode", mode)
                .putInt("temp", temp)
                .putInt("fan", fan)
                .putBoolean("swingV", swingV)
                .putBoolean("swingH", swingH)
                .putBoolean("turbo", turbo)
                .putBoolean("sleep", sleep)
                .putBoolean("light", light)
                .putInt("timerMins", timerMins)
                .putLong("timerSetAt", timerSetAt)
                .putInt("clockOffset", clockOffsetMins)
                .putInt("ledBit", ledBit)
                .putInt("turboBit", turboBit)
                .putInt("sleepBit", sleepBit)
                .putInt("cleanBit", cleanBit)
                .apply();
    }

    public void clamp() {
        if (type < 0 || type > 2) type = 0;
        if (mode < 0 || mode > 4) mode = MODE_COOL;
        if (fan < 0 || fan > 3) fan = FAN_AUTO;
        temp = Math.max(minTemp(), Math.min(TEMP_MAX, temp));
    }

    /** Type B (Coolix) can't go below 17 °C. */
    public int minTemp() { return type == 1 ? 17 : TEMP_MIN; }

    /** Minutes left on a running timer, or 0. */
    public int timerRemainingMins(long now) {
        if (timerMins <= 0 || timerSetAt <= 0) return 0;
        long end = timerSetAt + timerMins * 60_000L;
        long left = end - now;
        return left <= 0 ? 0 : (int) Math.ceil(left / 60_000.0);
    }
}
