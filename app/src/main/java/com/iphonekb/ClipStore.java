package com.iphonekb;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Clipboard history: newest first, pinned items never expire.
 * Stored only in the app's private folder; saved on a background thread.
 */
final class ClipStore {
    static final int MAX_ITEM_CHARS = 100_000;
    private static final long MAX_TOTAL_CHARS = 30_000_000L;
    private static final int FILE_VERSION = 1;

    static final class Clip {
        final String text;
        final long time;
        boolean pinned;

        Clip(String text, long time, boolean pinned) {
            this.text = text;
            this.time = time;
            this.pinned = pinned;
        }
    }

    private final File file;
    private final ArrayList<Clip> items = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "clipboard-save");
            t.setDaemon(true);
            return t;
        }
    });
    private final AtomicBoolean savePending = new AtomicBoolean();
    private boolean loaded;
    private int max = 5000;
    private int version;

    ClipStore(File file) {
        this.file = file;
    }

    synchronized void setMax(int max) {
        this.max = Math.max(1, max);
        load();
        if (trim()) saveAsync();
    }

    int version() {
        return version;
    }

    synchronized int size() {
        load();
        return items.size();
    }

    synchronized Clip latest() {
        load();
        Clip best = null;
        for (Clip c : items) if (best == null || c.time > best.time) best = c;
        return best;
    }

    /** Pinned first, then everything else; newest first in both groups. */
    synchronized List<Clip> ordered(String query) {
        load();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        ArrayList<Clip> pinned = new ArrayList<>(), rest = new ArrayList<>();
        for (Clip c : items) {
            if (!q.isEmpty() && !c.text.toLowerCase(Locale.ROOT).contains(q)) continue;
            (c.pinned ? pinned : rest).add(c);
        }
        pinned.addAll(rest);
        return pinned;
    }

    synchronized boolean add(String text) {
        if (text == null) return false;
        if (text.trim().isEmpty()) return false;
        if (text.length() > MAX_ITEM_CHARS) text = text.substring(0, MAX_ITEM_CHARS);
        load();
        if (!items.isEmpty() && items.get(0).text.equals(text)) return false;
        boolean pinned = false;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).text.equals(text)) {
                pinned = items.remove(i).pinned;
                break;
            }
        }
        items.add(0, new Clip(text, System.currentTimeMillis(), pinned));
        trim();
        changed();
        return true;
    }

    synchronized void setPinned(Clip c, boolean pinned) {
        c.pinned = pinned;
        changed();
    }

    synchronized void remove(Clip c) {
        items.remove(c);
        changed();
    }

    synchronized void clearUnpinned() {
        load();
        ArrayList<Clip> keep = new ArrayList<>();
        for (Clip c : items) if (c.pinned) keep.add(c);
        items.clear();
        items.addAll(keep);
        changed();
    }

    private void changed() {
        version++;
        saveAsync();
    }

    /** Drops the oldest unpinned items beyond the size limit. */
    private boolean trim() {
        boolean removed = false;
        int unpinned = 0;
        long total = 0;
        for (Clip c : items) {
            total += c.text.length();
            if (!c.pinned) unpinned++;
        }
        for (int i = items.size() - 1; i >= 0 && (unpinned > max || total > MAX_TOTAL_CHARS); i--) {
            Clip c = items.get(i);
            if (c.pinned) continue;
            items.remove(i);
            unpinned--;
            total -= c.text.length();
            removed = true;
        }
        if (removed) version++;
        return removed;
    }

    // ---------------------------------------------------------------- storage

    private void load() {
        if (loaded) return;
        loaded = true;
        if (!file.exists()) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            int v = in.readInt();
            if (v != FILE_VERSION) return;
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                boolean pinned = in.readBoolean();
                long time = in.readLong();
                int len = in.readInt();
                if (len < 0 || len > MAX_ITEM_CHARS * 4) break;
                byte[] b = new byte[len];
                in.readFully(b);
                items.add(new Clip(new String(b, StandardCharsets.UTF_8), time, pinned));
            }
        } catch (IOException ignored) {
            // A damaged file loses only what could not be read.
        }
        version++;
    }

    /** Saves in the background; many quick changes are written once. */
    private void saveAsync() {
        if (!savePending.compareAndSet(false, true)) return;
        io.execute(new Runnable() {
            @Override
            public void run() {
                savePending.set(false);
                String[] texts;
                long[] times;
                boolean[] pins;
                synchronized (ClipStore.this) {
                    int n = items.size();
                    texts = new String[n];
                    times = new long[n];
                    pins = new boolean[n];
                    for (int i = 0; i < n; i++) {
                        Clip c = items.get(i);
                        texts[i] = c.text;
                        times[i] = c.time;
                        pins[i] = c.pinned;
                    }
                }
                write(texts, times, pins);
            }
        });
    }

    private void write(String[] texts, long[] times, boolean[] pins) {
        File tmp = new File(file.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(FILE_VERSION);
            out.writeInt(texts.length);
            for (int i = 0; i < texts.length; i++) {
                byte[] b = texts[i].getBytes(StandardCharsets.UTF_8);
                out.writeBoolean(pins[i]);
                out.writeLong(times[i]);
                out.writeInt(b.length);
                out.write(b);
            }
        } catch (IOException e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(file);
    }

    /** Waits for pending writes (tests and shutdown). */
    void flush() {
        try {
            io.submit(new Runnable() {
                @Override
                public void run() {
                }
            }).get();
        } catch (Exception ignored) {
        }
    }
}
