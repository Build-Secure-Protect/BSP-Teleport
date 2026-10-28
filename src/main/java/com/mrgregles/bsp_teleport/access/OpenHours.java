package com.mrgregles.bsp_teleport.access;

import com.mrgregles.bsp_teleport.TeleportConfig;

import javax.annotation.Nullable;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Daily open hours in real-world time (T82, D150 to D158): up to {@link #MAX} windows, the same every day, in the zone of
 * {@code portals.timeZone}. Used by Real clock portals and by the TIME rule on warps and portals. Windows are minutes of the
 * day, open from {@code open} until {@code close}; one may cross midnight (22:00-02:00), and the whole day is 00:00-24:00.
 * Windows that touch or overlap are joined; a window whose start equals its end is refused.
 */
public final class OpenHours {
    public static final int MAX = 4;
    public static final int DAY = 1440;

    /** One window: open at {@code open}, closed again at {@code close} (minutes of the day; close 1440 only for the whole day). */
    public record Window(int open, int close) {
        boolean contains(int minute) {
            return open < close ? minute >= open && minute < close : minute >= open || minute < close;
        }

        int length() {
            return open < close ? close - open : DAY - open + close;
        }
    }

    public static final OpenHours ALL_DAY = new OpenHours(List.of(new Window(0, DAY)));

    private final List<Window> windows;

    private OpenHours(List<Window> windows) {
        this.windows = List.copyOf(windows);
    }

    public List<Window> windows() {
        return windows;
    }

    /**
     * The joined hours of these windows, sorted from midnight, or null when a window is empty or out of range, or more than
     * {@link #MAX} windows are left after joining.
     */
    @Nullable
    public static OpenHours of(List<Window> raw) {
        if (raw.isEmpty()) {
            return null;
        }
        boolean[] open = new boolean[DAY];
        for (Window w : raw) {
            if (w.open() < 0 || w.open() >= DAY || w.close() < 0 || w.close() > DAY || w.open() == w.close() % DAY && !(w.open() == 0 && w.close() == DAY)) {
                return null;
            }
            if (w.close() == DAY && w.open() == 0) {
                return ALL_DAY;
            }
            int close = w.close() % DAY;
            for (int m = w.open(); m != close; m = (m + 1) % DAY) {
                open[m] = true;
            }
        }
        int start = -1;
        for (int m = 0; m < DAY; m++) {
            if (!open[m]) {
                start = m;
                break;
            }
        }
        if (start < 0) {
            return ALL_DAY;
        }
        // Walk the circle once from a closed minute, so a window across midnight comes out as one.
        List<Window> out = new ArrayList<>();
        int runStart = -1;
        for (int i = 1; i <= DAY; i++) {
            int m = (start + i) % DAY;
            if (open[m] && runStart < 0) {
                runStart = m;
            } else if (!open[m] && runStart >= 0) {
                out.add(new Window(runStart, m));
                runStart = -1;
            }
        }
        if (out.size() > MAX) {
            return null;
        }
        out.sort(java.util.Comparator.comparingInt(Window::open));
        return new OpenHours(out);
    }

    /**
     * Hours from text: windows as {@code HH:MM-HH:MM} separated by commas or spaces ("15:00-17:00, 03:00-05:00"), or plain
     * times in pairs ("15:00 17:00", the old Real clock command form). Null when it cannot be read.
     */
    @Nullable
    public static OpenHours parse(String text) {
        if (text == null) {
            return null;
        }
        List<Window> raw = new ArrayList<>();
        List<Integer> loose = new ArrayList<>();
        for (String part : text.trim().split("[,;\\s]+")) {
            if (part.isEmpty()) {
                continue;
            }
            int dash = part.indexOf('-');
            if (dash >= 0) {
                int open = time(part.substring(0, dash), false), close = time(part.substring(dash + 1), true);
                if (open < 0 || close < 0) {
                    return null;
                }
                raw.add(new Window(open, close));
            } else {
                int t = time(part, loose.size() % 2 == 1);
                if (t < 0) {
                    return null;
                }
                loose.add(t);
            }
        }
        if (loose.size() % 2 != 0) {
            return null;
        }
        for (int i = 0; i < loose.size(); i += 2) {
            raw.add(new Window(loose.get(i), loose.get(i + 1)));
        }
        return of(raw);
    }

    /** Minutes of the day from HH:MM (24:00 allowed as an end), or -1. */
    static int time(String text, boolean end) {
        String[] p = text.trim().split(":");
        if (p.length != 2 || p[0].isEmpty() || p[1].length() != 2) {
            return -1;
        }
        try {
            int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
            if (end && h == 24 && m == 0) {
                return DAY;
            }
            return h < 0 || h > 23 || m < 0 || m > 59 ? -1 : h * 60 + m;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** These hours with more windows joined in, or null when that leaves more than {@link #MAX}. */
    @Nullable
    public OpenHours plus(OpenHours other) {
        List<Window> all = new ArrayList<>(windows);
        all.addAll(other.windows);
        return of(all);
    }

    /** These hours without window {@code index}, or null when none would be left. */
    @Nullable
    public OpenHours without(int index) {
        List<Window> rest = new ArrayList<>(windows);
        if (index < 0 || index >= rest.size()) {
            return this;
        }
        rest.remove(index);
        return rest.isEmpty() ? null : new OpenHours(rest);
    }

    public boolean isOpen(int minute) {
        for (Window w : windows) {
            if (w.contains(minute)) {
                return true;
            }
        }
        return false;
    }

    /** When the window open at {@code minute} closes, or -1 when closed then (or open all day). */
    public int closesAt(int minute) {
        for (Window w : windows) {
            if (w.contains(minute)) {
                return w.length() == DAY ? -1 : w.close() % DAY;
            }
        }
        return -1;
    }

    /** The next opening after {@code minute}, or -1 when open then. */
    public int nextOpen(int minute) {
        if (isOpen(minute)) {
            return -1;
        }
        int best = -1, bestWait = DAY + 1;
        for (Window w : windows) {
            int wait = Math.floorMod(w.open() - minute, DAY);
            if (wait < bestWait) {
                bestWait = wait;
                best = w.open();
            }
        }
        return best;
    }

    /** "15:00-17:00,03:00-05:00" as stored and sent; {@link #parse} reads it back. */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        for (Window w : windows) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(hhmm(w.open())).append('-').append(hhmm(w.close()));
        }
        return sb.toString();
    }

    /** "03:00-05:00, 15:00-17:00" for screens and chat. */
    public String describe() {
        return encode().replace(",", ", ");
    }

    public static String hhmm(int minute) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }

    /** {@code portals.timeZone}, or this machine's zone when empty or not a zone. */
    public static ZoneId zone() {
        String id = TeleportConfig.getOr(TeleportConfig.PORTAL_TIME_ZONE, "");
        try {
            return id.isEmpty() ? ZoneId.systemDefault() : ZoneId.of(id);
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    /** The minute of the day now, in {@link #zone()}. */
    public static int now() {
        return LocalTime.now(zone()).toSecondOfDay() / 60;
    }
}
