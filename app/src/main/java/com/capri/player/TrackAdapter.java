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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TrackAdapter extends BaseAdapter {
    private final Context ctx;
    private final List<Track> all = new ArrayList<>();
    private final List<Track> shown = new ArrayList<>();
    private final LayoutInflater inf;
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private static final LruCache<String, Bitmap> ARTS = new LruCache<String, Bitmap>(250) {};

    public TrackAdapter(Context c) {
        ctx = c;
        inf = LayoutInflater.from(c);
    }

    public void setAll(List<Track> tracks) {
        all.clear();
        all.addAll(tracks);
        applyFilter("");
    }

    public void applyFilter(String q) {
        shown.clear();
        String s = (q == null) ? "" : q.toLowerCase(Locale.ROOT).trim();
        if (s.isEmpty()) {
            shown.addAll(all);
        } else {
            for (Track t : all) {
                String hay = (t.title + " " + t.artist + " " + t.album).toLowerCase(Locale.ROOT);
                if (hay.contains(s)) shown.add(t);
            }
        }
        notifyDataSetChanged();
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
        h.t2.setText((t.artist == null || t.artist.isEmpty() ? "Artista desconhecido" : t.artist)
                + (t.album == null || t.album.isEmpty() ? "" : " · " + t.album));
        h.du.setText(fmt(t.duration));
        h.art.setTag(t.data);

        final Bitmap cached = ARTS.get(t.data);
        if (cached != null) {
            h.art.setImageBitmap(cached);
        } else {
            h.art.setImageResource(0);
            h.art.setBackgroundColor(0xFF17171D);
            ex.execute(() -> {
                final Bitmap b = getArt(t.data);
                if (b != null) ARTS.put(t.data, b);
                h.art.post(() -> {
                    if (t.data.equals(h.art.getTag())) {
                        if (b != null) h.art.setImageBitmap(b);
                        else h.art.setImageResource(0);
                    }
                });
            });
        }
        return cv;
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    /** Le a capa embutida no MP3 (APIC). Thread-safe com cache. */
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
