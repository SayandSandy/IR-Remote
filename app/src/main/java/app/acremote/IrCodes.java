package app.acremote;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the raw infrared timings (microseconds, mark/space alternating) for each button.
 *
 * Type A / C: TCL 112-bit protocol. This is the format the IRext / Mi Remote database lists
 *             for Videocon ACs (header bytes 23 CB 26 01). Every press sends the whole AC state,
 *             including the display-light bit.
 * Type B:     Coolix (Midea) 24-bit protocol, used by many Indian-market rebadged ACs.
 *             Light, Auto Clean, Sleep, Turbo and Swing are separate toggle commands.
 *
 * Timings and bit layouts follow the open-source IRremoteESP8266 library (ir_Tcl.cpp, ir_Coolix.cpp).
 */
public final class IrCodes {

    public static final int CARRIER_HZ = 38000;

    public enum Cmd { STATE, LED, CLEAN, SLEEP, TURBO, SWING_V, SWING_H, TIMER }

    public static final class Result {
        public final int[] pattern;      // null if nothing to send
        public final String note;        // shown to the user when the AC type can't do this
        public final boolean phoneTimer; // Type B timer is run by the phone instead
        Result(int[] p, String n, boolean t) { pattern = p; note = n; phoneTimer = t; }
    }

    public static Result build(AcState s, Cmd cmd) {
        switch (s.type) {
            case 1: return coolix(s, cmd);
            case 2: return tcl(s, cmd, false);
            default: return tcl(s, cmd, true);
        }
    }

    // ---------------------------------------------------------------- TCL 112

    private static final int TCL_HDR_MARK = 3000, TCL_HDR_SPACE = 1650;
    private static final int TCL_BIT_MARK = 500, TCL_ONE = 1050, TCL_ZERO = 325;

    /** Candidate bit positions (byte*8 + bit) for the Button Finder, most likely first. */
    static final int[] FINDER_BITS = {
            12 * 8 + 4,  // seen set in the Videocon entry of the IRext database
            5 * 8 + 0, 5 * 8 + 1, 5 * 8 + 7, 6 * 8 + 4, 6 * 8 + 6, 6 * 8 + 7, 8 * 8 + 7,
            12 * 8 + 6, 12 * 8 + 0, 12 * 8 + 1, 12 * 8 + 2, 12 * 8 + 5, 5 * 8 + 5, 8 * 8 + 6,
            4 * 8 + 0, 4 * 8 + 1, 4 * 8 + 2, 4 * 8 + 3, 4 * 8 + 4, 4 * 8 + 5, 4 * 8 + 6, 4 * 8 + 7,
            11 * 8 + 0, 11 * 8 + 1, 11 * 8 + 2, 11 * 8 + 3, 11 * 8 + 4, 11 * 8 + 5, 11 * 8 + 6, 11 * 8 + 7,
            7 * 8 + 4, 7 * 8 + 5, 7 * 8 + 6, 7 * 8 + 7,
            9 * 8 + 0, 9 * 8 + 7, 10 * 8 + 0, 10 * 8 + 7,
            5 * 8 + 6, 6 * 8 + 5   // the standard TCL light / turbo positions
    };

    static byte[] tclBytes(AcState s, boolean isTcl, boolean timerPress) {
        return tclBytes(s, isTcl, timerPress, false, -1);
    }

    /**
     * @param cleanPulse  include the Auto Clean bit (one-shot) if one has been assigned
     * @param flipBit     Button Finder: invert this bit (byte*8+bit), or -1
     */
    static byte[] tclBytes(AcState s, boolean isTcl, boolean timerPress, boolean cleanPulse, int flipBit) {
        int[] b = new int[14];
        b[0] = 0x23; b[1] = 0xCB; b[2] = 0x26; b[3] = 0x01; b[4] = 0x00;

        // A running timer is repeated in every message, otherwise the AC would cancel it.
        int mins = timerPress ? s.timerMins : s.timerRemainingMins(System.currentTimeMillis());
        int offTimer = 0, onTimer = 0;
        if (mins > 0) {
            int units = Math.min(36, (mins + 19) / 20); // 20-minute steps, 12 h max
            if (s.power) offTimer = units; else onTimer = units;
        }

        boolean ledCustom = s.ledBit >= 0, turboCustom = s.turboBit >= 0, sleepCustom = s.sleepBit >= 0;

        // Byte 5: power, timers, light (standard TCL: bit set = light OFF)
        b[5] = 0x20;
        if (s.power) b[5] |= 1 << 2;
        if (offTimer > 0) b[5] |= 1 << 3;
        if (onTimer > 0) b[5] |= 1 << 4;
        if (!s.light && !ledCustom) b[5] |= 1 << 6;

        // Byte 6: mode + turbo
        int mode;
        switch (s.mode) {
            case AcState.MODE_AUTO: mode = 8; break;
            case AcState.MODE_DRY: mode = 2; break;
            case AcState.MODE_FAN: mode = 7; break;
            case AcState.MODE_HEAT: mode = 1; break;
            default: mode = 3; // cool
        }
        b[6] = mode;
        if (s.turbo && !turboCustom) b[6] |= 1 << 5;

        // Byte 7: temperature, stored as 31 - °C
        int t = Math.max(16, Math.min(31, s.temp));
        b[7] = (31 - t) & 0x0F;

        // Byte 8: fan, vertical swing, timer indicator
        int fan;
        if ((s.turbo && !turboCustom) || s.mode == AcState.MODE_FAN && s.fan == AcState.FAN_AUTO) fan = 5;
        else if (s.sleep && !sleepCustom) fan = 1; // "night" speed
        else {
            switch (s.fan) {
                case AcState.FAN_LOW: fan = 2; break;
                case AcState.FAN_MED: fan = 3; break;
                case AcState.FAN_HIGH: fan = 5; break;
                default: fan = 0;
            }
        }
        b[8] = fan | ((s.swingV ? 0b111 : 0) << 3) | (1 << 6);

        b[9] = (offTimer & 0x3F) << 1;
        b[10] = (onTimer & 0x3F) << 1;
        b[11] = 0;
        b[12] = (s.swingH ? 1 << 3 : 0) | (isTcl ? 1 << 7 : 0);

        // Bits found with the Button Finder: "active" = the opposite of the normal value.
        if (ledCustom && !s.light) flip(b, s.ledBit);
        if (turboCustom && s.turbo) flip(b, s.turboBit);
        if (sleepCustom && s.sleep) flip(b, s.sleepBit);
        if (s.cleanBit >= 0 && cleanPulse) flip(b, s.cleanBit);
        if (flipBit >= 0) flip(b, flipBit);

        int sum = 0;
        for (int i = 0; i < 13; i++) sum += b[i];
        b[13] = sum & 0xFF;

        byte[] out = new byte[14];
        for (int i = 0; i < 14; i++) out[i] = (byte) b[i];
        return out;
    }

    private static void flip(int[] b, int pos) {
        int idx = pos / 8, bit = pos % 8;
        if (idx >= 3 && idx <= 12) b[idx] ^= 1 << bit; // never touch the header bytes or checksum
    }

    static int[] tclPattern(byte[] bytes) {
        List<Integer> p = new ArrayList<>();
        p.add(TCL_HDR_MARK);
        p.add(TCL_HDR_SPACE);
        for (byte by : bytes) {
            for (int bit = 0; bit < 8; bit++) {           // LSB first
                p.add(TCL_BIT_MARK);
                p.add(((by >> bit) & 1) == 1 ? TCL_ONE : TCL_ZERO);
            }
        }
        p.add(TCL_BIT_MARK);                               // footer
        return toArray(p);
    }

    /** Button Finder: current state with one candidate bit inverted. */
    static int[] finderPattern(AcState s, int flipBit) {
        return tclPattern(tclBytes(s, s.type != 2, false, false, flipBit));
    }

    private static Result tcl(AcState s, Cmd cmd, boolean isTcl) {
        if (cmd == Cmd.CLEAN && s.cleanBit < 0) {
            return new Result(null, "Auto Clean isn't set up yet. Open the Button Finder to find it.", false);
        }
        byte[] bytes = tclBytes(s, isTcl, cmd == Cmd.TIMER, cmd == Cmd.CLEAN, -1);
        return new Result(tclPattern(bytes), null, false);
    }

    // ---------------------------------------------------------------- Coolix

    private static final int CX_TICK = 276;
    private static final int CX_BIT_MARK = 2 * CX_TICK, CX_ONE = 6 * CX_TICK, CX_ZERO = 2 * CX_TICK;
    private static final int CX_HDR_MARK = 17 * CX_TICK, CX_HDR_SPACE = 16 * CX_TICK;
    private static final int CX_GAP = 19 * CX_TICK;

    static final int CX_OFF = 0xB27BE0, CX_SWING = 0xB26BE0, CX_SLEEP = 0xB2E003,
            CX_TURBO = 0xB5F5A2, CX_LED = 0xB5F5A5, CX_CLEAN = 0xB5F5AA;
    private static final int[] CX_TEMP = {
            0b0000, 0b0001, 0b0011, 0b0010, 0b0110, 0b0111, 0b0101,
            0b0100, 0b1100, 0b1101, 0b1001, 0b1000, 0b1010, 0b1011}; // 17..30 °C

    static int coolixState(AcState s) {
        if (!s.power) return CX_OFF;
        int modeBits, tempBits;
        int t = Math.max(17, Math.min(30, s.temp));
        tempBits = CX_TEMP[t - 17];
        switch (s.mode) {
            case AcState.MODE_DRY: modeBits = 0b01; break;
            case AcState.MODE_AUTO: modeBits = 0b10; break;
            case AcState.MODE_HEAT: modeBits = 0b11; break;
            case AcState.MODE_FAN: modeBits = 0b01; tempBits = 0b1110; break; // fan = dry + special temp
            default: modeBits = 0b00; // cool
        }
        int fanBits;
        switch (s.fan) {
            case AcState.FAN_LOW: fanBits = 0b100; break;
            case AcState.FAN_MED: fanBits = 0b010; break;
            case AcState.FAN_HIGH: fanBits = 0b001; break;
            default:
                fanBits = (s.mode == AcState.MODE_AUTO || s.mode == AcState.MODE_DRY) ? 0b000 : 0b101;
        }
        int byte1 = (fanBits << 5) | 0x1F;          // 0x1F = "no sensor temperature"
        int byte0 = (tempBits << 4) | (modeBits << 2);
        return (0xB2 << 16) | (byte1 << 8) | byte0;
    }

    private static Result coolix(AcState s, Cmd cmd) {
        int code;
        switch (cmd) {
            case LED: code = CX_LED; break;
            case CLEAN: code = CX_CLEAN; break;
            case SLEEP: code = CX_SLEEP; break;
            case TURBO: code = CX_TURBO; break;
            case SWING_V: code = CX_SWING; break;
            case SWING_H:
                return new Result(null, "Left–right swing has no separate code on Type B.", false);
            case TIMER:
                return new Result(null, null, true);
            default: code = coolixState(s);
        }
        return new Result(coolixPattern(code), null, false);
    }

    static int[] coolixPattern(int code) {
        List<Integer> p = new ArrayList<>();
        for (int frame = 0; frame < 2; frame++) {          // sent twice, like the real remote
            p.add(CX_HDR_MARK);
            p.add(CX_HDR_SPACE);
            for (int shift = 16; shift >= 0; shift -= 8) {
                int seg = (code >> shift) & 0xFF;
                addBitsMsb(p, seg);
                addBitsMsb(p, seg ^ 0xFF);                 // each byte followed by its inverse
            }
            p.add(CX_BIT_MARK);
            if (frame == 0) p.add(CX_GAP);
        }
        return toArray(p);
    }

    private static void addBitsMsb(List<Integer> p, int value) {
        for (int bit = 7; bit >= 0; bit--) {
            p.add(CX_BIT_MARK);
            p.add(((value >> bit) & 1) == 1 ? CX_ONE : CX_ZERO);
        }
    }

    private static int[] toArray(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    private IrCodes() {}
}
