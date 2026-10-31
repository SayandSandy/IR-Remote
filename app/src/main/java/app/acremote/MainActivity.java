package app.acremote;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.hardware.ConsumerIrManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Locale;

public class MainActivity extends Activity {

    static final String PREFS = "ac_remote";
    private static final int SET_NONE = 0, SET_CLOCK = 1, SET_TIMER = 2;

    private final AcState s = new AcState();
    private SharedPreferences prefs;
    private ConsumerIrManager ir;
    private Ui.Palette pal;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // LCD views
    private View lcdContent;
    private TextView modeText, clockText, tempText, unitText, fanText, hintText, chipsText, irDot;
    private Ui.FanBars fanBars;
    private TextView messageText;

    // keys
    private Ui.PowerKey powerKey;
    private Ui.Key kLed, kFan, kSleep, kTurbo, kSwingH, kSwingV, kClock, kTimer, kHr, kMin;

    private int setMode = SET_NONE;
    private int draftMins;          // timer length or clock minutes-of-day being edited

    private final Runnable tick = new Runnable() {
        @Override public void run() { renderClockAndTimer(); handler.postDelayed(this, 15_000); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        s.load(prefs);
        ir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
        pal = Ui.Palette.of(this);

        Window w = getWindow();
        w.setStatusBarColor(pal.bg);
        w.setNavigationBarColor(pal.bg);
        int flags = 0;
        if (!pal.dark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flags);

        setContentView(buildUi());
        renderAll(false);
    }

    @Override protected void onResume() {
        super.onResume();
        s.load(prefs); // the timer receiver may have changed power while we were away
        renderAll(false);
        handler.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        s.save(prefs);
    }

    // ================================================================= layout

    private int dp(float v) { return Ui.dp(this, v); }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(pal.bg);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(12), dp(16), dp(28));
        scroll.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- Title
        TextView title = Ui.text(this, "AC Remote", 34, pal.label, false);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        col.addView(title, lp(-1, -2, 0, dp(8), 0, 0));
        TextView sub = Ui.text(this, "Videocon · remote 175A", 15, pal.secondary, false);
        col.addView(sub, lp(-1, -2, 0, dp(4), 0, dp(16)));

        if (ir == null || !ir.hasIrEmitter()) {
            TextView warn = Ui.text(this,
                    "This phone has no IR blaster, so the buttons can't reach the AC.", 15, pal.red, false);
            warn.setBackground(Ui.rounded(pal.card, dp(14)));
            warn.setPadding(dp(14), dp(12), dp(14), dp(12));
            warn.setLineSpacing(0, 1.2f);
            col.addView(warn, lp(-1, -2, 0, 0, 0, dp(16)));
        }

        // ---- AC type picker
        Ui.Segmented seg = new Ui.Segmented(this, pal, new String[]{"Type A", "Type B", "Type C"});
        seg.select(s.type, false);
        seg.setListener(idx -> {
            s.type = idx;
            s.clamp();
            s.save(prefs);
            renderAll(true);
            showMessage("Switched to " + new String[]{"Type A", "Type B", "Type C"}[idx]
                    + ". Press Power to test it.");
        });
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        segLp.bottomMargin = dp(6);
        col.addView(seg, segLp);
        TextView segHint = Ui.text(this, "If your AC doesn't beep, try the next type.", 13, pal.secondary, false);
        segHint.setGravity(Gravity.CENTER);
        col.addView(segHint, lp(-1, -2, 0, dp(2), 0, dp(12)));

        // ---- LCD card
        col.addView(buildLcd(), lp(-1, -2, 0, 0, 0, dp(10)));

        messageText = Ui.text(this, "", 13, pal.secondary, false);
        messageText.setGravity(Gravity.CENTER);
        messageText.setMinHeight(dp(30));
        messageText.setLineSpacing(0, 1.15f);
        messageText.setAlpha(0f);
        col.addView(messageText, lp(-1, -2, dp(8), 0, dp(8), dp(6)));

        // ---- Power + temperature
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        powerKey = new Ui.PowerKey(this, pal);
        powerKey.onTap(this::onPower);
        top.addView(powerKey, new LinearLayout.LayoutParams(dp(68), dp(68)));

        LinearLayout tempPill = new LinearLayout(this);
        tempPill.setBackground(Ui.rounded(pal.card, dp(34)));
        tempPill.setGravity(Gravity.CENTER_VERTICAL);
        tempPill.addView(tempKey("−", "Temperature down", -1), new LinearLayout.LayoutParams(0, -1, 1));
        View divider = new View(this);
        divider.setBackgroundColor(pal.separator);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(1), dp(28));
        tempPill.addView(divider, dl);
        tempPill.addView(tempKey("+", "Temperature up", +1), new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, dp(68), 1);
        tl.leftMargin = dp(12);
        top.addView(tempPill, tl);
        col.addView(top, lp(-1, -2, 0, 0, 0, dp(12)));

        // ---- Key grid, same order as the physical remote
        Ui.Key kMode = key("MODE", this::onMode);
        Ui.Key kClean = key("AUTO CLEAN", this::onClean);
        kLed = key("LED DISPLAY", this::onLed);
        kFan = key("FAN", this::onFan);
        kSleep = key("SLEEP", this::onSleep);
        kTurbo = key("TURBO", this::onTurbo);
        kSwingH = key("SWING ↔", this::onSwingH);
        kSwingV = key("SWING ↕", this::onSwingV);
        kHr = key("HR", () -> onAdjust(true));
        kMin = key("MIN", () -> onAdjust(false));
        kClock = key("CLOCK", this::onClock);
        kTimer = key("TIMER", this::onTimer);

        col.addView(row(kMode, kClean, kLed));
        col.addView(row(kFan, kSleep, kTurbo));
        col.addView(row(kSwingH, kSwingV));
        col.addView(row(kHr, kMin));
        col.addView(row(kClock, kTimer));

        return scroll;
    }

    private View buildLcd() {
        FrameLayout card = new FrameLayout(this);
        card.setBackground(Ui.rounded(pal.card, dp(26)));
        card.setPadding(dp(20), dp(16), dp(20), dp(18));

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        lcdContent = c;
        card.addView(c, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        modeText = Ui.text(this, "", 15, pal.label, true);
        header.addView(modeText, new LinearLayout.LayoutParams(0, -2, 1));
        irDot = Ui.text(this, "IR", 11, pal.accent, true);
        irDot.setAlpha(0f);
        irDot.setPadding(0, 0, dp(8), 0);
        header.addView(irDot);
        clockText = Ui.text(this, "", 15, pal.secondary, true);
        header.addView(clockText);
        c.addView(header);

        LinearLayout tempRow = new LinearLayout(this);
        tempRow.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        tempText = Ui.text(this, "24", 76, pal.label, false);
        tempText.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        tempText.setLetterSpacing(-0.03f);
        tempRow.addView(tempText);
        unitText = Ui.text(this, "°C", 26, pal.secondary, false);
        unitText.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(-2, -2);
        ul.topMargin = dp(14);
        ul.leftMargin = dp(2);
        tempRow.addView(unitText, ul);
        c.addView(tempRow, lp(-1, -2, 0, dp(6), 0, dp(4)));

        LinearLayout fanRow = new LinearLayout(this);
        fanRow.setGravity(Gravity.CENTER);
        fanBars = new Ui.FanBars(this, pal);
        fanRow.addView(fanBars, new LinearLayout.LayoutParams(dp(26), dp(16)));
        fanText = Ui.text(this, "", 14, pal.secondary, true);
        fanText.setPadding(dp(8), 0, 0, 0);
        fanRow.addView(fanText);
        c.addView(fanRow);

        chipsText = Ui.text(this, "", 13, pal.accent, true);
        chipsText.setGravity(Gravity.CENTER);
        c.addView(chipsText, lp(-1, -2, 0, dp(12), 0, 0));

        hintText = Ui.text(this, "", 13, pal.secondary, false);
        hintText.setGravity(Gravity.CENTER);
        hintText.setLineSpacing(0, 1.15f);
        c.addView(hintText, lp(-1, -2, 0, dp(6), 0, 0));
        return card;
    }

    private Ui.PressButton tempKey(String glyph, String desc, int delta) {
        Ui.PressButton b = new Ui.PressButton(this);
        TextView t = Ui.text(this, glyph, 30, pal.label, false);
        t.setGravity(Gravity.CENTER);
        b.addView(t, new FrameLayout.LayoutParams(-1, -1));
        b.setContentDescription(desc);
        b.onTap(() -> onTemp(delta));
        return b;
    }

    private Ui.Key key(String label, Runnable r) {
        Ui.Key k = new Ui.Key(this, pal, label);
        k.onTap(r);
        return k;
    }

    private LinearLayout row(View... keys) {
        LinearLayout r = new LinearLayout(this);
        for (int i = 0; i < keys.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(50), 1);
            if (i > 0) p.leftMargin = dp(10);
            r.addView(keys[i], p);
        }
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.bottomMargin = dp(8);
        r.setLayoutParams(rp);
        return r;
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                w == -1 ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                h == -1 ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }

    // ================================================================= button actions

    private void onPower() {
        exitSetMode();
        s.power = !s.power;
        if (!s.power) { s.turbo = false; s.sleep = false; }
        if (s.timerMins > 0) {              // pressing Power cancels a pending timer
            s.timerMins = 0;
            s.timerSetAt = 0;
            TimerReceiver.cancel(this);
        }
        send(IrCodes.Cmd.STATE);
    }

    private void onTemp(int delta) {
        if (s.mode == AcState.MODE_FAN) { showMessage("Temperature can't be changed in Fan mode."); return; }
        int before = s.temp;
        s.temp = Math.max(s.minTemp(), Math.min(AcState.TEMP_MAX, s.temp + delta));
        if (s.temp == before) {
            nudge(tempText, delta);
            showMessage(delta > 0 ? "Highest temperature is " + AcState.TEMP_MAX + " °C."
                    : "Lowest temperature is " + s.minTemp() + " °C.");
            return;
        }
        renderAll(false);
        slideTemp(delta);
        sendIfOn(IrCodes.Cmd.STATE);
    }

    private void onMode() {
        s.mode = (s.mode + 1) % 5;
        sendIfOn(IrCodes.Cmd.STATE);
    }

    private void onFan() {
        s.fan = (s.fan + 1) % 4;
        s.turbo = false;
        sendIfOn(IrCodes.Cmd.STATE);
    }

    private void onClean() { sendIfOn(IrCodes.Cmd.CLEAN); }

    private void onLed() {
        s.light = !s.light;
        sendIfOn(IrCodes.Cmd.LED);
        if (s.power) showMessage(s.light ? "Display light on." : "Display light off.");
    }

    private void onSleep() { s.sleep = !s.sleep; sendIfOn(IrCodes.Cmd.SLEEP); }

    private void onTurbo() { s.turbo = !s.turbo; sendIfOn(IrCodes.Cmd.TURBO); }

    private void onSwingH() {
        if (s.type == 1) { // no code on Type B; don't change state
            showMessage("Left–right swing has no separate code on Type B.");
            return;
        }
        s.swingH = !s.swingH;
        sendIfOn(IrCodes.Cmd.SWING_H);
    }

    private void onSwingV() { s.swingV = !s.swingV; sendIfOn(IrCodes.Cmd.SWING_V); }

    private void onClock() {
        if (setMode == SET_CLOCK) {
            int phone = minutesOfDay();
            s.clockOffsetMins = ((draftMins - phone) % 1440 + 1440) % 1440;
            exitSetMode();
            s.save(prefs);
            showMessage("Clock set. (It's shown on this remote only.)");
            renderAll(false);
            return;
        }
        setMode = SET_CLOCK;
        draftMins = (minutesOfDay() + s.clockOffsetMins) % 1440;
        renderAll(false);
    }

    private void onTimer() {
        if (setMode == SET_TIMER) {
            s.timerMins = draftMins;
            s.timerSetAt = draftMins > 0 ? System.currentTimeMillis() : 0;
            exitSetMode();
            sendTimer();
            return;
        }
        setMode = SET_TIMER;
        int left = s.timerRemainingMins(System.currentTimeMillis());
        draftMins = left > 0 ? roundTimer(left) : 60;
        renderAll(false);
    }

    private void onAdjust(boolean hour) {
        if (setMode == SET_NONE) {
            nudge(hintText, 1);
            showMessage("Press CLOCK or TIMER first, then use HR and MIN.");
            return;
        }
        if (setMode == SET_CLOCK) {
            draftMins = (draftMins + (hour ? 60 : 1)) % 1440;
        } else {
            int step = hour ? 60 : timerStep();
            draftMins += step;
            if (draftMins > 12 * 60) draftMins = hour ? draftMins % 60 : 0;
        }
        renderAll(false);
    }

    private int timerStep() { return s.type == 1 ? 10 : 20; }

    private int roundTimer(int mins) {
        int st = timerStep();
        return Math.min(720, ((mins + st - 1) / st) * st);
    }

    private void exitSetMode() { setMode = SET_NONE; }

    // ================================================================= sending

    private void sendIfOn(IrCodes.Cmd cmd) {
        if (!s.power) {
            renderAll(false);
            s.save(prefs);
            showMessage("The AC is off. Press Power first; this setting will be used when it turns on.");
            return;
        }
        send(cmd);
    }

    private void send(IrCodes.Cmd cmd) {
        IrCodes.Result r = IrCodes.build(s, cmd);
        renderAll(false);
        s.save(prefs);
        if (r.note != null) { showMessage(r.note); return; }
        if (r.pattern != null) transmit(r.pattern);
    }

    private void sendTimer() {
        IrCodes.Result r = IrCodes.build(s, IrCodes.Cmd.TIMER);
        boolean turnOn = !s.power;
        String what = (turnOn ? "turn on" : "turn off");
        if (r.phoneTimer) {
            TimerReceiver.cancel(this);
            if (s.timerMins > 0) {
                TimerReceiver.schedule(this, s.timerMins, turnOn);
                showMessage("This phone will " + what + " the AC in " + fmtDuration(s.timerMins)
                        + ". Leave it where it can see the AC.");
            } else {
                showMessage("Timer cancelled.");
            }
        } else if (r.pattern != null) {
            transmit(r.pattern);
            showMessage(s.timerMins > 0 ? "AC will " + what + " in " + fmtDuration(s.timerMins) + "."
                    : "Timer cancelled.");
        }
        s.save(prefs);
        renderAll(false);
    }

    private void transmit(int[] pattern) {
        if (ir == null || !ir.hasIrEmitter()) {
            showMessage("No IR blaster found on this phone.");
            return;
        }
        try {
            ir.transmit(IrCodes.CARRIER_HZ, pattern);
            flashIr();
            if (Build.VERSION.SDK_INT >= 30) {
                lcdContent.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            }
        } catch (Exception e) {
            showMessage("Couldn't send: " + e.getMessage());
        }
    }

    // ================================================================= rendering

    private void renderAll(boolean animate) {
        boolean on = s.power;
        powerKey.setOn(on);

        String[] glyph = {"A", "❄︎", "◌", "✲", "☀︎"};
        modeText.setText(glyph[s.mode] + "  " + AcState.MODE_NAMES[s.mode]);

        if (!on) {
            tempText.setText("Off");
            unitText.setVisibility(View.GONE);
        } else if (s.mode == AcState.MODE_FAN) {
            tempText.setText("Fan");
            unitText.setVisibility(View.GONE);
        } else {
            tempText.setText(String.valueOf(s.temp));
            unitText.setVisibility(View.VISIBLE);
        }

        int level;
        String fanLabel;
        if (s.turbo) { level = 4; fanLabel = "Turbo"; }
        else if (s.fan == AcState.FAN_AUTO) { level = 0; fanLabel = "Fan Auto"; }
        else { level = s.fan + (s.fan == AcState.FAN_HIGH ? 1 : 0); fanLabel = "Fan " + AcState.FAN_NAMES[s.fan]; }
        fanBars.setLevel(level);
        fanText.setText(fanLabel);

        StringBuilder chips = new StringBuilder();
        if (s.swingV) chips.append("Swing ↕   ");
        if (s.swingH) chips.append("Swing ↔   ");
        if (s.sleep) chips.append("Sleep   ");
        if (s.turbo) chips.append("Turbo   ");
        if (!s.light) chips.append("Display off   ");
        int left = s.timerRemainingMins(System.currentTimeMillis());
        if (left > 0) chips.append("Timer ").append(fmtDuration(left));
        chipsText.setText(chips.toString().trim());
        chipsText.setVisibility(chips.length() == 0 ? View.GONE : View.VISIBLE);

        float target = on ? 1f : 0.45f;
        if (animate && Ui.motionEnabled()) {
            lcdContent.animate().alpha(target).setDuration(Ui.Spring.duration(0.35f))
                    .setInterpolator(Ui.SPRING).start();
        } else {
            lcdContent.setAlpha(target);
        }
        if (setMode != SET_NONE) lcdContent.setAlpha(1f);

        kLed.setActive(!s.light);
        kFan.setActive(false);
        kSleep.setActive(s.sleep);
        kTurbo.setActive(s.turbo);
        kSwingH.setActive(s.swingH);
        kSwingV.setActive(s.swingV);
        kClock.setActive(setMode == SET_CLOCK);
        kTimer.setActive(setMode == SET_TIMER);
        kHr.setAlpha(setMode == SET_NONE ? 0.55f : 1f);
        kMin.setAlpha(setMode == SET_NONE ? 0.55f : 1f);

        renderClockAndTimer();
    }

    private void renderClockAndTimer() {
        if (clockText == null) return;
        if (setMode == SET_CLOCK) {
            clockText.setText(fmtClock(draftMins));
            clockText.setTextColor(pal.accent);
            hintText.setText("Set clock with HR / MIN, then press CLOCK.");
        } else if (setMode == SET_TIMER) {
            clockText.setText(fmtClock((minutesOfDay() + s.clockOffsetMins) % 1440));
            clockText.setTextColor(pal.secondary);
            String what = s.power ? "Off" : "On";
            hintText.setText(draftMins == 0
                    ? "Timer: none. Press TIMER to cancel any timer."
                    : what + " timer: " + fmtDuration(draftMins) + ". Press TIMER to send.");
        } else {
            clockText.setText(fmtClock((minutesOfDay() + s.clockOffsetMins) % 1440));
            clockText.setTextColor(pal.secondary);
            hintText.setText("");
        }
        hintText.setVisibility(hintText.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private static int minutesOfDay() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private static String fmtClock(int m) {
        return String.format(Locale.US, "%d:%02d", m / 60, m % 60);
    }

    private static String fmtDuration(int m) {
        if (m < 60) return m + " min";
        if (m % 60 == 0) return (m / 60) + " h";
        return (m / 60) + " h " + (m % 60) + " min";
    }

    // ================================================================= motion & feedback

    /** New number slides in from the direction of the press (up = from below). */
    private void slideTemp(int delta) {
        if (!Ui.motionEnabled()) return;
        tempText.setTranslationY(delta > 0 ? dp(14) : -dp(14));
        tempText.setAlpha(0.35f);
        tempText.animate().translationY(0).alpha(1f)
                .setDuration(Ui.Spring.duration(0.32f)).setInterpolator(Ui.SPRING).start();
    }

    /** Small rubber-band nudge at a limit: moves a little, then settles back. */
    private void nudge(View v, int dir) {
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (!Ui.motionEnabled()) return;
        v.animate().cancel();
        v.setTranslationY(dir > 0 ? -dp(5) : dp(5));
        v.animate().translationY(0).setDuration(Ui.Spring.duration(0.4f))
                .setInterpolator(Ui.SPRING).start();
    }

    /** "IR" lights up the moment the signal goes out, like the LCD icon on the remote. */
    private void flashIr() {
        irDot.animate().cancel();
        irDot.setAlpha(1f);
        irDot.animate().alpha(0f).setStartDelay(220)
                .setDuration(Ui.motionEnabled() ? 420 : 0).start();
    }

    private final Runnable hideMessage = () -> {
        if (Ui.motionEnabled()) messageText.animate().alpha(0f).setDuration(260).start();
        else messageText.setAlpha(0f);
    };

    private void showMessage(String msg) {
        messageText.setText(msg);
        messageText.animate().cancel();
        if (Ui.motionEnabled()) messageText.animate().alpha(1f).setDuration(160).start();
        else messageText.setAlpha(1f);
        handler.removeCallbacks(hideMessage);
        handler.postDelayed(hideMessage, 4500);
    }
}
