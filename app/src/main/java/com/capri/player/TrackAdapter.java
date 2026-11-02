package com.capri.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TrackAdapter extends BaseAdapter {

    private final Context ctx;
    private final List<Track> all = new ArrayList<>();
    private final List<Track> shown = new ArrayList<>();
    private final LayoutInflater inf;
    private final ExecutorService ex = Executors.newSingleThreadExecutor();

    private int sortMode = 0; // 0 titulo,1 artista,2 album,3 data,4 tamanho,5 tocadas
    private String query = "";
    private int showCountMode = 0; // 0 = duracao, 1 = numero de vezes tocada, 2 = tamanho em MB
    private Map<Long, Integer> plays = new HashMap<Long, Integer>();

    private static final LruCache<String, Bitmap> ARTS = new LruCache<String, Bitmap>(250) {};

    public TrackAdapter(Context c) {
        ctx = c;
        inf = LayoutInflater.from(c);
    }

    public void setPlayCounts(Map<Long, Integer> m) {
        plays = m;
    }

    public void replaceAll(List<Track> ts) {
        all.clear();
        all.addAll(ts);
        rebuild();
    }

    public void setSortMode(int m) {
        sortMode = m;
        rebuild();
    }

    public int getSortMode() { return sortMode; }

    public void setShowCountMode(int m) {
        showCountMode = m;
        notifyDataSetChanged();
    }

    public void applyFilter(String q) {
        this.query = q;
        rebuild();
    }

    private void rebuild() {
        shown.clear();
        String s = query.toLowerCase(Locale.ROOT).trim();
        if (s.isEmpty()) {
            shown.addAll(all);
        } else {
            for (Track t : all) {
                String hay = (t.title + " " + t.artist + " " + t.album).toLowerCase(Locale.ROOT);
                if (hay.contains(s)) shown.add(t);
            }
        }
        final Locale loc = Locale.ROOT;
        Collections.sort(shown, new Comparator<Track>() {
            @Override
            public int compare(Track a, Track b) {
                switch (sortMode) {
                    case 1:
                        return safe(a.artist).compareTo(safe(b.artist));
                    case 2:
                        return safe(a.album).compareTo(safe(b.album));
                    case 3:
                        return Long.compare(b.dateAdded, a.dateAdded);
                    case 4:
                        return Long.compare(b.size, a.size);
                    case 5:
                        int ca = plays.containsKey(a.id) ? plays.get(a.id) : 0;
                        int cb = plays.containsKey(b.id) ? plays.get(b.id) : 0;
                        if (cb != ca) return Integer.compare(cb, ca);
                        return a.title.compareTo(b.title);
                    default:
                        return a.title.compareTo(b.title);
                }
            }
        });
        notifyDataSetChanged();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    public List<Track> getShown() { return shown; }

    @Override public int getCount() { return shown.size(); }
    @Override public Object getItem(int position) { return shown.get(position); }
    @Override public long getItemId(int position) { return shown.get(position).id; }

    @Override
    public View getView(int position, View cv, ViewGroup parent) {
        VH h;
        if (cv == null) {
            cv = inf.inflate(R.layout.item_track, parent, false);
            h = new VH();
            h.art = cv.findViewById(R.id.art);
            h.t1 = cv.findViewById(R.id.t1);
            h.t2 = cv.findViewById(R.id.t2);
            h.du = cv.findViewById(R.id.du);
            cv.setTag(h);
        } else {
            h = (VH) cv.getTag();
        }
        final Track t = shown.get(position);
        h.t1.setText(t.title);
        h.t2.setText((t.artist == null || t.artist.isEmpty()
                ? ctx.getString(R.string.unknown_artist) : t.artist)
                + (t.album == null || t.album.isEmpty() ? "" : " · " + t.album));

        if (showCountMode == 1) {
            Integer pc = plays.get(t.id);
            h.du.setText((pc == null ? 0 : pc) + "✕");
            h.du.setTextColor(0xFF3DFF5E);
        } else if (showCountMode == 2) {
            float mb = t.size / 1048576f;
            h.du.setText(String.format(Locale.ROOT, "%.1fMB", mb));
            h.du.setTextColor(0xFF9A9AA6);
        } else {
            long ms = t.duration;
            long s = Math.max(0, ms / 1000);
            h.du.setText((s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60));
            h.du.setTextColor(0xFF9A9AA6);
        }

        final String path = t.data;
        h.art.setTag(path);
        final Bitmap cached = ARTS.get(path);
        if (cached != null) {
            h.art.setImageBitmap(cached);
        } else {
            h.art.setImageResource(0);
            final ImageView iv = h.art;
            ex.execute(new Runnable() {
                @Override public void run() {
                    final Bitmap b = getArt(path);
                    if (b != null) ARTS.put(path, b);
                    iv.post(new Runnable() {
                        @Override public void run() {
                            if (path.equals(iv.getTag())) {
                                if (b != null) iv.setImageBitmap(b);
                                else iv.setImageResource(0);
                            }
                        }
                    });
                }
            });
        }
        return cv;
    }

    public Bitmap loadArt(String path) {
        Bitmap c = ARTS.get(path);
        if (c != null) return c;
        Bitmap b = getArt(path);
        if (b != null) ARTS.put(path, b);
        return b;
    }

    /** Le a capa embutida no MP3 (APIC), com cache. */
    public static Bitmap peekArt(String path) {
        Bitmap c = ARTS.get(path);
        if (c != null) return c;
        Bitmap b = getArt(path);
        if (b != null) ARTS.put(path, b);
        return b;
    }

    /** Le a capa embutida no MP3 (APIC). */
    public static Bitmap getArt(String path) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            byte[] b = r.getEmbeddedPicture();
            if (b == null || b.length == 0) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            return BitmapFactory.decodeByteArray(b, 0, b.length, o);
        } catch (Throwable e) {
            return null;
        } finally {
            try { r.release(); } catch (Throwable ignore) {}
        }
    }

    static class VH {
        ImageView art;
        TextView t1, t2, du;
    }
}
