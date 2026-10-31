package app.acremote;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small UI toolkit: palette, critically-damped spring, press-to-shrink buttons, segmented control. */
final class Ui {

    // ------------------------------------------------------------ palette (iOS system colors)
    static final class Palette {
        final int bg, card, label, secondary, fill, accent, accentSoft, green, red, separator;
        final boolean dark;
        Palette(boolean dark) {
            this.dark = dark;
            if (dark) {
                bg = 0xFF000000; card = 0xFF1C1C1E; label = 0xFFFFFFFF; secondary = 0x99EBEBF5;
                fill = 0x3D767680; accent = 0xFF0A84FF; accentSoft = 0x330A84FF;
                green = 0xFF30D158; red = 0xFFFF453A; separator = 0x5C545458;
            } else {
                bg = 0xFFF2F2F7; card = 0xFFFFFFFF; label = 0xFF000000; secondary = 0x993C3C43;
                fill = 0x1F767680; accent = 0xFF007AFF; accentSoft = 0x1F007AFF;
                green = 0xFF34C759; red = 0xFFFF3B30; separator = 0x4A3C3C43;
            }
        }
        static Palette of(Context c) {
            int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return new Palette(night == Configuration.UI_MODE_NIGHT_YES);
        }
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static boolean motionEnabled() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    static TextView text(Context c, String s, float sp, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        // Size-specific tracking: tighten large text, loosen tiny text.
        if (sp >= 28) t.setLetterSpacing(-0.02f);
        else if (sp <= 12) t.setLetterSpacing(0.02f);
        return t;
    }

    /**
     * Critically damped spring (damping ratio 1.0): x(t) = 1 - (1 + ωt)e^(-ωt).
     * No overshoot; settles smoothly. ViewPropertyAnimator always starts from the
     * current on-screen value, so presses can be interrupted and reversed at any time.
     */
    static final class Spring implements TimeInterpolator {
        private static final float K = 9.2f; // ωt at which the spring is within 0.1% of target
        private static final float NORM = 1f - (1f + K) * (float) Math.exp(-K);
        @Override public float getInterpolation(float x) {
            float wt = K * x;
            return (1f - (1f + wt) * (float) Math.exp(-wt)) / NORM;
        }
        /** Settle time for a given "response" (seconds), in ms. */
        static long duration(float response) {
            return Math.round(K / (2 * Math.PI / response) * 1000);
        }
    }

    static final Spring SPRING = new Spring();

    // ------------------------------------------------------------ pressable button

    /**
     * Highlights instantly on touch-down (shrink + haptic), commits on touch-up,
     * and cancels if the finger slides away (with a little extra hit slop).
     */
    static class PressButton extends FrameLayout {
        private final float slop;
        private boolean pressedInside;
        private Runnable action;

        PressButton(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop() + dp(c, 10);
            setClickable(true);
            setFocusable(true);
            setHapticFeedbackEnabled(true);
        }

        void onTap(Runnable r) { action = r; }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressedInside = true;
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    animateTo(0.94f, 0.85f, Spring.duration(0.18f));
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    boolean inside = e.getX() > -slop && e.getY() > -slop
                            && e.getX() < getWidth() + slop && e.getY() < getHeight() + slop;
                    if (inside != pressedInside) {
                        pressedInside = inside;
                        if (inside) animateTo(0.94f, 0.85f, Spring.duration(0.18f));
                        else animateTo(1f, 1f, Spring.duration(0.3f));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    animateTo(1f, 1f, Spring.duration(0.3f));
                    if (pressedInside) {
                        pressedInside = false;
                        performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressedInside = false;
                    animateTo(1f, 1f, Spring.duration(0.3f));
                    return true;
            }
            return super.onTouchEvent(e);
        }

        @Override public boolean performClick() {
            super.performClick();
            if (action != null) action.run();
            return true;
        }

        private void animateTo(float scale, float alpha, long ms) {
            if (!motionEnabled()) { setAlpha(alpha); return; } // reduced motion: dim only
            animate().scaleX(scale).scaleY(scale).alpha(alpha)
                    .setDuration(ms).setInterpolator(SPRING).start();
        }
    }

    /** Rounded key with a label, like the physical remote's buttons. */
    static final class Key extends PressButton {
        final TextView label;
        private final Palette pal;
        private final float radius;
        private boolean active;

        Key(Context c, Palette pal, String text) {
            super(c);
            this.pal = pal;
            radius = dp(c, 16);
            label = text(c, text, 13, pal.label, true);
            label.setGravity(android.view.Gravity.CENTER);
            label.setLetterSpacing(0.04f);
            label.setAllCaps(false);
            addView(label, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            setMinimumHeight(dp(c, 54));
            setActive(false);
            setContentDescription(text);
        }

        void setActive(boolean on) {
            active = on;
            setBackground(rounded(on ? pal.accentSoft : pal.card, radius));
            label.setTextColor(on ? pal.accent : pal.label);
        }

        boolean isActive() { return active; }
    }

    /** Circular power key with a drawn power glyph. */
    static final class PowerKey extends PressButton {
        private final Palette pal;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        private boolean on;

        PowerKey(Context c, Palette pal) {
            super(c);
            this.pal = pal;
            setWillNotDraw(false);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(dp(c, 2.6f));
            setContentDescription("Power");
            setOn(false);
        }

        void setOn(boolean v) {
            on = v;
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(on ? pal.green : pal.card);
            setBackground(d);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            paint.setColor(on ? Color.WHITE : pal.green);
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) * 0.38f;
            arc.set(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(arc, -60, 300, false, paint);
            c.drawLine(cx, cy - r * 1.25f, cx, cy - r * 0.15f, paint);
        }
    }

    /** iOS-style segmented control with a sliding thumb. */
    static final class Segmented extends FrameLayout {
        interface Listener { void onSelected(int index); }

        private final View thumb;
        private final LinearLayout row;
        private final Palette pal;
        private final String[] items;
        private int selected;
        private Listener listener;

        Segmented(Context c, Palette pal, String[] items) {
            super(c);
            this.pal = pal;
            this.items = items;
            int pad = dp(c, 2);
            setPadding(pad, pad, pad, pad);
            setBackground(rounded(pal.fill, dp(c, 10)));

            thumb = new View(c);
            GradientDrawable td = rounded(pal.dark ? 0xFF636366 : 0xFFFFFFFF, dp(c, 8));
            thumb.setBackground(td);
            thumb.setElevation(pal.dark ? 0 : dp(c, 1.5f));
            addView(thumb, new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));

            row = new LinearLayout(c);
            for (int i = 0; i < items.length; i++) {
                TextView t = text(c, items[i], 14, pal.label, true);
                t.setGravity(android.view.Gravity.CENTER);
                t.setPadding(0, dp(c, 8), 0, dp(c, 8));
                final int idx = i;
                t.setOnClickListener(v -> select(idx, true));
                row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            }
            row.setElevation(dp(c, 2));
            addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void setListener(Listener l) { listener = l; }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            int segW = (w - getPaddingLeft() - getPaddingRight()) / items.length;
            ViewGroup.LayoutParams lp = thumb.getLayoutParams();
            lp.width = segW;
            thumb.setLayoutParams(lp);
            thumb.setTranslationX(segW * selected);
        }

        void select(int idx, boolean fromUser) {
            if (idx == selected && fromUser) return;
            selected = idx;
            int segW = thumb.getWidth();
            if (segW > 0) {
                if (motionEnabled() && fromUser) {
                    thumb.animate().translationX(segW * idx)
                            .setDuration(Spring.duration(0.3f)).setInterpolator(SPRING).start();
                } else {
                    thumb.setTranslationX(segW * idx);
                }
            }
            if (fromUser) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                if (listener != null) listener.onSelected(idx);
            }
        }
    }

    /** Four rising bars showing fan speed. */
    static final class FanBars extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Palette pal;
        private final RectF r = new RectF();
        private int level; // 0..4

        FanBars(Context c, Palette pal) { super(c); this.pal = pal; }

        void setLevel(int l) { level = l; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            int n = 4;
            float gap = getWidth() * 0.12f;
            float w = (getWidth() - gap * (n - 1)) / n;
            for (int i = 0; i < n; i++) {
                float h = getHeight() * (0.35f + 0.65f * (i + 1) / n);
                float x = i * (w + gap);
                r.set(x, getHeight() - h, x + w, getHeight());
                paint.setColor(i < level ? pal.label : pal.fill);
                c.drawRoundRect(r, w / 2, w / 2, paint);
            }
        }
    }

    private Ui() {}
}
