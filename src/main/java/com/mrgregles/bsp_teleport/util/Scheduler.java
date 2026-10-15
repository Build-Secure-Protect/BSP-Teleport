package com.mrgregles.bsp_teleport.util;

import net.minecraftforge.event.TickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Runs things on the server thread after a number of ticks. */
public final class Scheduler {
    private static final List<Entry> QUEUE = new ArrayList<>();

    private static final class Entry {
        int ticks;
        final Runnable task;

        Entry(int ticks, Runnable task) {
            this.ticks = ticks;
            this.task = task;
        }
    }

    private Scheduler() {
    }

    /** Call only from the server thread. */
    public static void later(int ticks, Runnable task) {
        QUEUE.add(new Entry(Math.max(1, ticks), task));
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || QUEUE.isEmpty()) {
            return;
        }
        List<Runnable> due = new ArrayList<>();
        Iterator<Entry> it = QUEUE.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (--e.ticks <= 0) {
                it.remove();
                due.add(e.task);
            }
        }
        due.forEach(Runnable::run);
    }

    public static void clear() {
        QUEUE.clear();
    }
}
