package com.capri.player;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int REQ_PERM = 11;
    private static final int REQ_NOTI = 12;
    private static final long K = 1048576L;

    private static final int TAB_ALL = 0;
    private static final int TAB_ARTIST = 1;
    private static final int TAB_ALBUM = 2;
    private static final int TAB_TOP = 3;

    private ListView lv;
    private GridView gv;
    private EditText search;
    private TextView nTitle, nArtist, cur, dur;
    private TextView fTitle, fArtist, fCur, fDur;
    private ImageView playBtn, shufBtn, repBtn, prevB, nextB;
    private ImageView fPlay, fPrev, fNext, fShuf, fRep;
    private TextView btnSort, btnGrid;
    private TextView tabAll, tabArtist, tabAlbum, tabTop;
    private ImageView nArt, fArt;
    private SeekBar bar, fBar;
    private View nowBar, fullRoot;

    private List<Track> all = new ArrayList<>();
    private final List<Object[]> groups = new ArrayList<>(); // {String name, Long count, String path}
    private List<Track> currentList = new ArrayList<>();

    private TrackAdapter adapter;
    private FolderAdapter folderAdapter;
    private GridAdapter gridAdapter;

    private PlayerService srv;
    private boolean bound, fullscreenOn = true;
    private int curTab = TAB_ALL;
    private String openGroup = null;
    private String lastArtPath = "";

    private SharedPreferences prefs, plays;
    private final Handler tick = new Handler();

    private final PlayerService.Listener listener = new PlayerService.Listener() {
        @Override public void onTrack(long trackId, String title, String artist, long duration, String path) {
            nTitle.setText(title == null ? "" : title);
            fTitle.setText(title == null ? "" : title);
            String a = artist == null || artist.isEmpty()
                    ? getString(R.string.unknown_artist) : artist;
            nArtist.setText(a);
            fArtist.setText(a);

            // conta o play pra aba "Mais ouvidas"
            String key = String.valueOf(trackId);
            int c = plays.getInt(key, 0) + 1;
            plays.edit().putInt(key, c).apply();
            if (curTab == TAB_TOP) rebuildRows();

            if (path != null && !path.equals(lastArtPath)) {
                lastArtPath = path;
                final String p = path;
                new Thread(() -> {
                    final Bitmap b = TrackAdapter.getArt(p);
                    runOnUiThread(() -> {
                        if (p.equals(nArt.getTag())) nArt.setImageBitmap(b);
                        if (p.equals(fArt.getTag())) fArt.setImageBitmap(b);
                    });
                }).start();
                nArt.setTag(path);
                fArt.setTag(path);
            }
        }
        @Override public void onPlayState(boolean playing, long position, long duration) {
            syncPlayState(playing, position, duration);
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (srv != null) {
                int p = (int) srv.pos();
                bar.setProgress(p);
                fBar.setProgress(p);
                cur.setText(fmt(p));
                fCur.setText(fmt(p));
            }
            tick.postDelayed(this, 500);
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            srv = ((PlayerService.LocalBinder) b).svc();
            srv.setListener(listener);
            refreshToggles();
        }
        @Override public void onServiceDisconnected(ComponentName n) { srv = null; }
    };

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("capri", Context.MODE_PRIVATE);
        plays = getSharedPreferences("plays", Context.MODE_PRIVATE);

        lv = findViewById(R.id.list);
        gv = findViewById(R.id.grid);
        search = findViewById(R.id.search);
        nowBar = findViewById(R.id.nowbar);
        fullRoot = findViewById(R.id.fullRoot);
        nTitle = findViewById(R.id.nt1);
        nArtist = findViewById(R.id.nt2);
        fTitle = findViewById(R.id.fnt1);
        fArtist = findViewById(R.id.fnt2);
        nArt = findViewById(R.id.nart);
        fArt = findViewById(R.id.fart);
        playBtn = findViewById(R.id.play);
        fPlay = findViewById(R.id.fplay);
        prevB = findViewById(R.id.prev);
        nextB = findViewById(R.id.next);
        fPrev = findViewById(R.id.fprev);
        fNext = findViewById(R.id.fnext);
        shufBtn = findViewById(R.id.shuf);
        repBtn = findViewById(R.id.rep);
        fShuf = findViewById(R.id.fshuf);
        fRep = findViewById(R.id.frep);
        btnSort = findViewById(R.id.btnSort);
        btnGrid = findViewById(R.id.btnGrid);
        tabAll = findViewById(R.id.tabAll);
        tabArtist = findViewById(R.id.tabArtist);
        tabAlbum = findViewById(R.id.tabAlbum);
        tabTop = findViewById(R.id.tabTop);
        bar = findViewById(R.id.seek);
        fBar = findViewById(R.id.fseek);
        cur = findViewById(R.id.cur);
        dur = findViewById(R.id.dur);
        fCur = findViewById(R.id.fcur);
        fDur = findViewById(R.id.fdur);

        adapter = new TrackAdapter(this);
        adapter.setPlayCounts(mapCopy(plays));
        int savedSort = prefs.getInt("sortMode", 0);
        adapter.setSortMode(savedSort);
        folderAdapter = new FolderAdapter();
        gridAdapter = new GridAdapter();
        lv.setAdapter(adapter);
        gv.setAdapter(gridAdapter);

        lv.setOnItemClickListener((parent, view, position, id) -> {
            if (folderOn()) {
                Object[] g = groups.get(position);
                openGroup = (String) g[0];
                rebuildRows();
                return;
            }
            List<Track> q = adapter.getShown();
            if (q != null && position < q.size()) playAt(q, position);
        });

        gv.setOnItemClickListener((parent, view, position, id) -> {
            List<Track> q = gridAdapter.items();
            if (q == null || position >= q.size()) return;
            playAt(q, position);
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b2, int c2) {}
            @Override public void onTextChanged(CharSequence c, int a, int b2, int c2) {
                adapter.applyFilter(c.toString());
                if (folderOn()) rebuildRows();
                gridAdapter.replace(adapter.getShown());
            }
            @Override public void afterTextChanged(Editable e) {}
        });

        playBtn.setOnClickListener(v -> { if (srv != null) srv.toggle(); });
        fPlay.setOnClickListener(v -> { if (srv != null) srv.toggle(); });
        nextB.setOnClickListener(v -> playFromRelative(1));
        prevB.setOnClickListener(v -> playFromRelative(-1));
        fNext.setOnClickListener(v -> playFromRelative(1));
        fPrev.setOnClickListener(v -> playFromRelative(-1));

        View.OnClickListener shufClick = v -> {
            if (srv != null) {
                boolean on = !srv.isShuffle();
                srv.setShuffle(on);
                refreshToggles();
                Toast.makeText(this, on ? "SHUFFLE ON" : "SHUFFLE OFF",
                        Toast.LENGTH_SHORT).show();
            }
        };
        shufBtn.setOnClickListener(shufClick);
        fShuf.setOnClickListener(shufClick);

        View.OnClickListener repClick = v -> {
            if (srv != null) {
                int m = (srv.getRepeat() + 1) % 3;   // 0 off,1 all,2 one
                srv.setRepeat(m);
                refreshToggles();
                Toast.makeText(this,
                        m == 0 ? "REPETIR OFF" : (m == 1 ? "REPETIR TODAS" : "REPETIR UMA"),
                        Toast.LENGTH_SHORT).show();
            }
        };
        repBtn.setOnClickListener(repClick);
        fRep.setOnClickListener(repClick);

        bar.setOnSeekBarChangeListener(seekSync(bar));
        fBar.setOnSeekBarChangeListener(seekSync(fBar));

        tabAll.setOnClickListener(v -> setTab(TAB_ALL));
        tabArtist.setOnClickListener(v -> setTab(TAB_ARTIST));
        tabAlbum.setOnClickListener(v -> setTab(TAB_ALBUM));
        tabTop.setOnClickListener(v -> setTab(TAB_TOP));

        btnSort.setOnClickListener(v -> showSortMenu());
        btnGrid.setOnClickListener(v -> {
            boolean g = !prefs.getBoolean("grid", false);
            prefs.edit().putBoolean("grid", g).apply();
            applyGridVisibility();
            Toast.makeText(this, g ? "Grade ON" : "Lista ON", Toast.LENGTH_SHORT).show();
        });

        nArt.setOnClickListener(v -> showFull(true));
        fArt.setOnClickListener(v -> { if (srv != null) srv.toggle(); });
        findViewById(R.id.openfull).setOnClickListener(v -> showFull(true));
        findViewById(R.id.fclose).setOnClickListener(v -> showFull(false));

        findViewById(R.id.fsbtn).setOnClickListener(v -> {
            if (fullscreenOn) {
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                fullscreenOn = false;
            } else {
                immersive();
                fullscreenOn = true;
            }
        });

        Intent si = new Intent(this, PlayerService.class);
        bindService(si, conn, Context.BIND_AUTO_CREATE);
        bound = true;

        applyGridVisibility();
        askPerms();
        immersive();
    }

    private boolean folderOn() {
        return curTab == TAB_ARTIST && openGroup == null
                || curTab == TAB_ALBUM && openGroup == null;
    }

    private SeekBar.OnSeekBarChangeListener seekSync(SeekBar src) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && srv != null) srv.seekTo(p);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        };
    }

    private Map<Long, Integer> mapCopy(SharedPreferences p) {
        Map<Long, Integer> m = new HashMap<>();
        Map<String, ?> raw = p.getAll();
        for (String k : raw.keySet()) {
            Object v = raw.get(k);
            if (v instanceof Integer) {
                try { m.put(Long.parseLong(k), (Integer) v); } catch (Exception ignored) {}
            }
        }
        return m;
    }

    private void refreshToggles() {
        if (srv == null) return;
        boolean shuf = srv.isShuffle();
        int rep = srv.getRepeat();
        int off = 0xFF9A9AA6;

        shufBtn.setAlpha(shuf ? 1f : 0.45f);
        fShuf.setAlpha(shuf ? 1f : 0.5f);
        int sc = shuf ? 0xFF3DFF5E : off;
        shufBtn.setColorFilter(sc);
        fShuf.setColorFilter(sc);
        shufBtn.setBackgroundResource(shuf ? R.drawable.btn_glow_green : R.drawable.btn_circle);
        fShuf.setBackgroundResource(shuf ? R.drawable.btn_glow_green : R.drawable.btn_circle);

        repBtn.setAlpha(rep > 0 ? 1f : 0.45f);
        fRep.setAlpha(rep > 0 ? 1f : 0.5f);
        int rc = rep > 0 ? 0xFFFF2445 : off;
        repBtn.setColorFilter(rc);
        fRep.setColorFilter(rc);
        repBtn.setImageResource(rep == 2 ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        fRep.setImageResource(rep == 2 ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        repBtn.setBackgroundResource(rep > 0 ? R.drawable.btn_glow_red : R.drawable.btn_circle);
        fRep.setBackgroundResource(rep > 0 ? R.drawable.btn_glow_red : R.drawable.btn_circle);
    }

    private void syncPlayState(boolean playing, long position, long duration) {
        playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        fPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        if (duration > 0) {
            bar.setMax((int) duration);
            fBar.setMax((int) duration);
        }
        bar.setProgress((int) position);
        fBar.setProgress((int) position);
        cur.setText(fmt(position));
        fCur.setText(fmt(position));
        dur.setText(fmt(duration));
        fDur.setText(fmt(duration));
    }

    private void playFromRelative(int dir) {
        if (srv == null) { Toast.makeText(this, "Iniciando…", Toast.LENGTH_SHORT).show(); return; }
        if (dir == 1) srv.next(); else srv.prev();
    }

    private void showFull(boolean open) {
        fullRoot.setVisibility(open ? View.VISIBLE : View.GONE);
    }

    private void playAt(List<Track> q, int position) {
        if (srv == null) { Toast.makeText(this, "Iniciando…", Toast.LENGTH_SHORT).show(); return; }
        currentList = q;
        srv.playQueue(new ArrayList<>(q), position);
        nowBar.setVisibility(View.VISIBLE);
        refreshToggles();
    }

    private void setTab(int t) {
        curTab = t;
        openGroup = null;
        rebuildRows();
        applyGridVisibility();
        tabAll.setBackgroundResource(t == TAB_ALL ? R.drawable.tab_on : R.drawable.tab_off);
        tabArtist.setBackgroundResource(t == TAB_ARTIST ? R.drawable.tab_on : R.drawable.tab_off);
        tabAlbum.setBackgroundResource(t == TAB_ALBUM ? R.drawable.tab_on : R.drawable.tab_off);
        tabTop.setBackgroundResource(t == TAB_TOP ? R.drawable.tab_on : R.drawable.tab_off);
        int onC = 0xFFF1F1F5, offC = 0xFF9A9AA6;
        tabAll.setTextColor(t == TAB_ALL ? onC : offC);
        tabArtist.setTextColor(t == TAB_ARTIST ? onC : offC);
        tabAlbum.setTextColor(t == TAB_ALBUM ? onC : offC);
        tabTop.setTextColor(t == TAB_TOP ? onC : offC);
    }

    /** Decide o conjunto de faixas em % de tab + busca */
    private List<Track> tracksForTab() {
        List<Track> base = all;
        if (curTab == TAB_TOP) return countSorted(base);
        if (curTab == TAB_ARTIST && openGroup != null)
            return filterGroup(base, true, openGroup);
        if (curTab == TAB_ALBUM && openGroup != null)
            return filterGroup(base, false, openGroup);
        return new ArrayList<>(base);
    }

    private List<Track> filterGroup(List<Track> in, boolean byArtist, String name) {
        List<Track> out = new ArrayList<>();
        for (Track t : in) {
            String g = byArtist ? safe(t.artist) : safe(t.album);
            String eff = g.isEmpty()
                    ? (byArtist ? getString(R.string.unknown_artist) : getString(R.string.unknown_album))
                    : g;
            if (eff.equals(name)) out.add(t);
        }
        return out;
    }

    private List<Track> countSorted(List<Track> in) {
        List<Track> out = new ArrayList<>(in);
        Map<Long, Integer> m = mapCopy(plays);
        Collections.sort(out, (a, b) -> {
            int ca = m.containsKey(a.id) ? m.get(a.id) : 0;
            int cb = m.containsKey(b.id) ? m.get(b.id) : 0;
            if (cb != ca) return Integer.compare(cb, ca);
            return a.title.compareTo(b.title);
        });
        return out;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private void rebuildRows() {
        String q = search.getText().toString();
        if (curTab == TAB_ALL || curTab == TAB_TOP || openGroup != null) {
            List<Track> src = tracksForTab();
            adapter.setPlayCounts(mapCopy(plays));
            adapter.replaceAll(src);
            adapter.applyFilter(q);
            adapter.setShowCountMode(curTab == TAB_TOP ? 1 : 0);
            currentList = adapter.getShown();
            lv.setAdapter(adapter);
            if (prefs.getBoolean("grid", false) && curTab == TAB_ALL)
                gridAdapter.replace(adapter.getShown());
        } else {
            buildGroups();
            lv.setAdapter(folderAdapter);
            folderAdapter.notifyDataSetChanged();
            gv.setVisibility(View.GONE);
        }
        applyGridVisibility();
    }

    private void buildGroups() {
        groups.clear();
        boolean byArtist = curTab == TAB_ARTIST;
        String q = search.getText().toString().toLowerCase(Locale.ROOT).trim();
        Map<String, Object[]> m = new HashMap<>();
        for (Track t : all) {
            String g = safe(byArtist ? t.artist : t.album);
            if (g.isEmpty()) g = byArtist
                    ? getString(R.string.unknown_artist) : getString(R.string.unknown_album);
            Object[] row = m.get(g);
            if (row == null) {
                row = new Object[]{g, 0, t.data};
                m.put(g, row);
                groups.add(row);
            }
            row[1] = (Integer) row[1] + 1;
        }
        if (!q.isEmpty()) {
            List<Object[]> keep = new ArrayList<>();
            for (Object[] r : groups) if (((String) r[0]).toLowerCase(Locale.ROOT).contains(q)) keep.add(r);
            groups.clear();
            groups.addAll(keep);
        }
        Collections.sort(groups, (a, b) -> ((String) a[0]).compareTo((String) b[0]));
    }

    private void applyGridVisibility() {
        boolean grid = prefs.getBoolean("grid", false) && curTab == TAB_ALL;
        gv.setVisibility(grid ? View.VISIBLE : View.GONE);
        lv.setVisibility(grid ? View.GONE : View.VISIBLE);
        if (grid) gridAdapter.replace(adapter.getShown());
    }

    private void showSortMenu() {
        String[] names = getResources()
                .getStringArray(R.array.sort_names);
        new AlertDialog.Builder(this)
                .setTitle("Ordenar por")
                .setItems(names, (d, which) -> {
                    adapter.setSortMode(which);
                    prefs.edit().putInt("sortMode", which).apply();
                    gridAdapter.replace(adapter.getShown());
                })
                .show();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fullscreenOn) immersive();
    }

    // --------------- permissões e scan ---------------

    private void askPerms() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.READ_MEDIA_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS}, REQ_PERM);
            } else if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTI);
            }
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] g) {
        super.onRequestPermissionsResult(code, p, g);
        if (code == REQ_PERM || code == REQ_NOTI) rescan();
    }

    private void rescan() {
        boolean granted =
                checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED
                || Build.VERSION.SDK_INT < 33;
        if (!granted) return;

        String[] proj = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.SIZE
        };
        List<Track> found = new ArrayList<>();
        try (Cursor c = getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                null, null, MediaStore.Audio.Media.TITLE)) {
            if (c != null) {
                int iD = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int iT = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int iA = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int iAl = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                int iDd = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                int iDu = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int iDa = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
                int iSz = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
                while (c.moveToNext()) {
                    Track t = new Track();
                    t.id = c.getLong(iD);
                    t.title = c.getString(iT);
                    t.artist = c.getString(iA);
                    t.album = c.getString(iAl);
                    t.data = c.getString(iDd);
                    t.duration = c.getLong(iDu);
                    t.dateAdded = c.getLong(iDa) * 1000L;
                    t.size = c.getLong(iSz);
                    if (t.title == null || t.title.isEmpty())
                        t.title = t.data == null ? "?" :
                                t.data.substring(t.data.lastIndexOf('/') + 1);
                    found.add(t);
                }
            }
        } catch (Throwable ignored) {}
        all = found;
        adapter.setPlayCounts(mapCopy(plays));
        rebuildRows();
        runOnUiThread(() ->
                Toast.makeText(this, found.size() + " músicas encontradas", Toast.LENGTH_LONG).show()
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        rescan();
        tick.postDelayed(ticker, 500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        tick.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        if (bound) { unbindService(conn); bound = false; }
        super.onDestroy();
    }

    // ------------------ FolderAdapter ------------------
    private class FolderAdapter extends BaseAdapter {
        @Override public int getCount() { return groups.size(); }
        @Override public Object getItem(int position) { return groups.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View cv, ViewGroup parent) {
            if (cv == null)
                cv = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_group, parent, false);
            Object[] g = groups.get(position);
            ImageView art = cv.findViewById(R.id.gart);
            TextView nm = cv.findViewById(R.id.gname);
            TextView ct = cv.findViewById(R.id.gcount);
            nm.setText((String) g[0]);
            ct.setText(((Integer) g[1]) + " música(s)");
            final String p = (String) g[2];
            art.setTag(p);
            new Thread(() -> {
                final Bitmap b = TrackAdapter.getArt(p);
                runOnUiThread(() -> {
                    if (p.equals(art.getTag())) art.setImageBitmap(b);
                });
            }).start();
            return cv;
        }
    }

    // ------------------ GridAdapter ------------------
    private class GridAdapter extends BaseAdapter {
        private final List<Track> data = new ArrayList<>();

        public void replace(List<Track> ts) {
            data.clear();
            data.addAll(ts);
            notifyDataSetChanged();
        }

        public List<Track> items() { return data; }

        @Override public int getCount() { return data.size(); }
        @Override public Object getItem(int position) { return data.get(position); }
        @Override public long getItemId(int position) { return data.get(position).id; }

        @Override
        public View getView(int position, View cv, ViewGroup parent) {
            View root = cv;
            VH g;
            if (root == null) {
                root = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_grid, parent, false);
                g = new VH();
                g.art = root.findViewById(R.id.gart);
                g.t1 = root.findViewById(R.id.gt1);
                g.t2 = root.findViewById(R.id.gt2);
                root.setTag(g);
            } else {
                g = (VH) root.getTag();
            }
            Track t = data.get(position);
            g.t1.setText(t.title);
            g.t2.setText(t.artist == null || t.artist.isEmpty()
                    ? getString(R.string.unknown_artist) : t.artist);
            final String p = t.data;
            g.art.setTag(p);
            Bitmap c = TrackAdapter.peekArt(p);
            if (c != null) {
                g.art.setImageBitmap(c);
            } else {
                g.art.setImageResource(0);
                new Thread(() -> {
                    final Bitmap b = TrackAdapter.peekArt(p);
                    runOnUiThread(() -> {
                        if (p.equals(g.art.getTag())) g.art.setImageBitmap(b);
                    });
                }).start();
            }
            return root;
        }
    }

    private static class VH {
        ImageView art;
        TextView t1, t2;
    }
}
