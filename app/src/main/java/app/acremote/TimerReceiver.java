package app.acremote;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.ConsumerIrManager;

/**
 * Type B ACs have no known built-in timer code, so the phone runs the timer:
 * when it fires, the phone sends the power on/off signal itself.
 * (The phone needs to be lying where it can "see" the AC.)
 */
public class TimerReceiver extends BroadcastReceiver {
    static final String EXTRA_TURN_ON = "turnOn";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        SharedPreferences prefs = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        AcState s = new AcState();
        s.load(prefs);
        s.power = intent.getBooleanExtra(EXTRA_TURN_ON, false);
        s.timerMins = 0;
        s.timerSetAt = 0;
        IrCodes.Result r = IrCodes.build(s, IrCodes.Cmd.STATE);
        ConsumerIrManager ir = (ConsumerIrManager) ctx.getSystemService(Context.CONSUMER_IR_SERVICE);
        if (ir != null && ir.hasIrEmitter() && r.pattern != null) {
            try { ir.transmit(IrCodes.CARRIER_HZ, r.pattern); } catch (Exception ignored) { }
        }
        s.save(prefs);
    }

    static PendingIntent pending(Context ctx, boolean turnOn) {
        Intent i = new Intent(ctx, TimerReceiver.class).putExtra(EXTRA_TURN_ON, turnOn);
        return PendingIntent.getBroadcast(ctx, 7, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context ctx, int minutes, boolean turnOn) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long at = System.currentTimeMillis() + minutes * 60_000L;
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, turnOn));
    }

    static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(ctx, false));
    }
}
