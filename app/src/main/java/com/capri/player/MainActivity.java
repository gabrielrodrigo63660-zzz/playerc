package com.capri.player;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_PERM = 11;
    private static final int REQ_NOTI = 12;

    private TrackAdapter adapter;
    private ListView lv;
    private EditText search;
    private TextView nTitle, nArtist, cur, dur;
    private ImageView nArt;
    private TextView playBtn, shufBtn, repBtn, prevB, nextB;
    private SeekBar bar;
    private View nowBar;

    private PlayerService srv;
    private boolean bound;
    private boolean fullscreen = true;
    private String lastArtPath = "";

    private final List<Track> all = new ArrayList<>();
    private final Handler tick = new Handler();

    // acao do servico -> UI (chamado da main thread pelo servico)
    private final PlayerService.Listener listener = new PlayerService.Listener() {
        @Override public void onTrack(String title, String artist, long duration, String path) {
            nTitle.setText(title == null ? "" : title);
            nArtist.setText(artist == null || artist.isEmpty() ? "Artista desconhecido" : artist);
            if (path != null && !path.equals(lastArtPath)) {
                lastArtPath = path;
                nArt.setTag(path);
                final String p = path;
                new Thread(() -> {
                    final android.graphics.Bitmap b = TrackAdapter.getArt(p);
                    runOnUiThread(() -> { if (p.equals(nArt.getTag())) nArt.setImageBitmap(b); });
                }).start();
            }
        }
        @Override public void onPlayState(boolean playing, long position, long duration) {
            playBtn.setText(playing ? "⏸" : "▶");
            if (duration > 0) bar.setMax((int) duration);
            bar.setProgress((int) position);
            dur.setText(fmt(duration));
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (srv != null) {
                bar.setProgress((int) srv.pos());
                cur.setText(fmt(srv.pos()));
            }
            tick.postDelayed(this, 500);
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            srv = ((PlayerService.LocalBinder) b).svc();
            srv.setListener(listener);
        }
        @Override public void onServiceDisconnected(ComponentName n) { srv = null; }
    };

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);

        lv = findViewById(R.id.list);
        search = findViewById(R.id.search);
        nowBar = findViewById(R.id.nowbar);
        nTitle = findViewById(R.id.nt1);
        nArtist = findViewById(R.id.nt2);
        nArt = findViewById(R.id.nart);
        playBtn = findViewById(R.id.play);
        nextB = findViewById(R.id.next);
        prevB = findViewById(R.id.prev);
        shufBtn = findViewById(R.id.shuf);
        repBtn = findViewById(R.id.rep);
        bar = findViewById(R.id.seek);
        cur = findViewById(R.id.cur);
        dur = findViewById(R.id.dur);

        adapter = new TrackAdapter(this);
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((parent, view, position, id) -> {
            List<Track> q = adapter.getShown();
            Track t = q.get(position);
            List<Track> queue = new ArrayList<>(q);
            startPlayback(queue, position);
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b2, int c2) {}
            @Override public void onTextChanged(CharSequence c, int a, int b2, int c2) {
                adapter.applyFilter(c.toString());
            }
            @Override public void afterTextChanged(Editable e) {}
        });

        playBtn.setOnClickListener(v -> { if (srv != null) srv.toggle(); });
        nextB.setOnClickListener(v -> { if (srv != null) srv.next(); });
        prevB.setOnClickListener(v -> { if (srv != null) srv.prev(); });
        shufBtn.setOnClickListener(v -> {
            if (srv != null) {
                boolean on = !srv.isShuffle();
                srv.setShuffle(on);
                shufBtn.setAlpha(on ? 1f : 0.45f);
                Toast.makeText(this, on ? "Shuffle ON" : "Shuffle OFF",
                        Toast.LENGTH_SHORT).show();
            }
        });
        repBtn.setOnClickListener(v -> {
            if (srv != null) {
                int m = (srv.getRepeat() + 1) % 3;   // 0 off,1 all,2 one
                srv.setRepeat(m);
                repBtn.setText(m == 2 ? "🔂" : "🔁");
                repBtn.setAlpha(m == 0 ? 0.45f : 1f);
                Toast.makeText(this,
                        m == 0 ? "Repetir OFF" : (m == 1 ? "Repetir todas" : "Repetir uma"),
                        Toast.LENGTH_SHORT).show();
            }
        });

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && srv != null) srv.seekTo(p);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        Intent si = new Intent(this, PlayerService.class);
        bindService(si, conn, Context.BIND_AUTO_CREATE);
        bound = true;

        findViewById(R.id.fsbtn).setOnClickListener(v -> {
            if (fullscreen) {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                fullscreen = false;
            } else {
                immersive();
                fullscreen = true;
            }
        });

        askPerms();
        immersive();
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

    private void startPlayback(List<Track> queue, int position) {
        if (srv == null) { Toast.makeText(this, "Iniciando…", Toast.LENGTH_SHORT).show(); return; }
        srv.playQueue(queue, position);
        nowBar.setVisibility(View.VISIBLE);
        shufBtn.setAlpha(srv.isShuffle() ? 1f : 0.45f);
        repBtn.setAlpha(srv.getRepeat() == 0 ? 0.45f : 1f);
    }

    /** Consulta as musicas pelo MediaStore (sem copiar nada) */
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
                MediaStore.Audio.Media.DURATION
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
                while (c.moveToNext()) {
                    Track t = new Track();
                    t.id = c.getLong(iD);
                    t.title = c.getString(iT);
                    t.artist = c.getString(iA);
                    t.album = c.getString(iAl);
                    t.data = c.getString(iDd);
                    t.duration = c.getLong(iDu);
                    if (t.title == null || t.title.isEmpty())
                        t.title = t.data == null ? "?" :
                                t.data.substring(t.data.lastIndexOf('/') + 1);
                    found.add(t);
                }
            }
        } catch (Throwable ignored) {}
        all.clear();
        all.addAll(found);
        adapter.setAll(all);
        runOnUiThread(() ->
                Toast.makeText(this, found.size() + " músicas encontradas",
                        Toast.LENGTH_LONG).show()
        );
    }

    private void askPerms() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.READ_MEDIA_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS}, REQ_PERM);
            } else if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTI);
            }
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                        REQ_PERM);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] g) {
        super.onRequestPermissionsResult(code, p, g);
        if (code == REQ_PERM || code == REQ_NOTI) rescan();
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
        if (hasFocus) immersive();
    }

    private String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
