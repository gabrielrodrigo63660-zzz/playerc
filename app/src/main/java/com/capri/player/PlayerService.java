package com.capri.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.Collections;
import java.util.List;

public class PlayerService extends Service {

    public interface Listener {
        void onTrack(String title, String artist, long duration, String path);
        void onPlayState(boolean playing, long position, long duration);
    }

    public class LocalBinder extends Binder {
        public PlayerService svc() { return PlayerService.this; }
    }

    private static final String CH = "playback";
    private static final int NOTI_ID = 7;

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Listener listener;

    private MediaPlayer mp;
    private List<Track> queue = Collections.emptyList();
    private int idx = -1;
    private MediaSession session;
    private boolean shuf = false;
    private int rep = 0; // 0 off, 1 all, 2 one

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(
                CH, "Reprodução", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        session = new MediaSession(this, "CapriPlayer");
        session.setActive(true);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { toggle(); }
            @Override public void onPause() { toggle(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long pos) { seekTo(pos); }
        });
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (i != null && i.getAction() != null) {
            switch (i.getAction()) {
                case "TOGGLE": toggle(); break;
                case "NEXT": next(); break;
                case "PREV": prev(); break;
                case "CLOSE": stopSelf(); break;
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    // ---------- API pra Activity ----------

    public void setListener(Listener l) { listener = l; }

    public void playQueue(List<Track> q, int start) {
        queue = q;
        idx = start;
        playInternal();
    }

    public void toggle() {
        synchronized (this) {
            if (mp == null) {
                if (idx >= 0 && idx < queue.size()) playInternal();
                return;
            }
            if (mp.isPlaying()) {
                mp.pause();
            } else {
                requestFocus();
                mp.start();
            }
        }
        pushState();
        refreshNotification();
    }

    public void next() {
        if (queue.isEmpty()) return;
        if (idx + 1 < queue.size()) idx++;
        else if (rep == 1) idx = 0;
        playInternal();
    }

    public void prev() {
        if (queue.isEmpty()) return;
        if (pos() > 3000) { seekTo(0); return; }
        idx = (idx - 1 + queue.size()) % queue.size();
        playInternal();
    }

    public void seekTo(long ms) {
        synchronized (this) {
            if (mp != null) mp.seekTo((int) ms);
        }
        pushState();
    }

    public void setShuffle(boolean on) { shuf = on; }
    public void setRepeat(int mode) { rep = mode; }
    public boolean isShuffle() { return shuf; }
    public int getRepeat() { return rep; }

    public boolean isPlaying() {
        try { return mp != null && mp.isPlaying(); } catch (Exception e) { return false; }
    }

    public long pos() {
        try { return mp != null ? mp.getCurrentPosition() : 0; } catch (Exception e) { return 0; }
    }

    public long dur() {
        try { return mp != null ? mp.getDuration() : 0; } catch (Exception e) { return 0; }
    }

    public int getIndex() { return idx; }

    // ---------- interno ----------

    private void playInternal() {
        if (idx < 0 || idx >= queue.size()) return;
        final Track t = queue.get(idx);

        try {
            startForeground(NOTI_ID, noti(t.title, "Carregando…", null));
        } catch (Throwable ignored) {}

        synchronized (this) {
            try {
                if (mp != null) { mp.stop(); mp.release(); mp = null; }
            } catch (Throwable ignored) {}

            try {
                mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
                mp.setDataSource(this, android.net.Uri.parse("file://" + t.data));
                requestFocus();
                mp.setOnCompletionListener(mp1 -> {
                    if (rep == 2) {
                        mp1.seekTo(0);
                        mp1.start();
                        pushState();
                    } else if (idx + 1 < queue.size() || rep == 1) {
                        main.post(() -> next());
                    }
                });
                final Listener l = listener;
                main.post(() -> {
                    if (l != null)
                        l.onTrack(t.title, t.artist, t.duration, t.data);
                });
                mp.setOnPreparedListener(mp1 -> {
                    mp1.start();
                    pushState();
                    refreshNotification();
                });
                mp.prepareAsync();
            } catch (Throwable e) {
                e.printStackTrace();
                main.post(() -> next());
            }
        }
    }

    private void pushState() {
        try {
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackState.ACTION_SEEK_TO;
            int st = isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(st, pos(), 1f)
                    .build());
        } catch (Throwable ignored) {}

        try {
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE,
                            idx >= 0 && idx < queue.size() ? queue.get(idx).title : "Capri Player")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST,
                            idx >= 0 && idx < queue.size()
                                    ? (queue.get(idx).artist == null ? "" : queue.get(idx).artist)
                                    : "")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, dur())
                    .build());
        } catch (Throwable ignored) {}

        final boolean playing = isPlaying();
        final long d = dur(), p = pos();
        main.post(() -> {
            if (listener != null) listener.onPlayState(playing, p, d);
        });
    }

    private void requestFocus() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        } catch (Throwable ignored) {}
    }

    /** Atualiza a notificacao com a capa doAlbum (em thread separada). */
    private void refreshNotification() {
        final int i = idx;
        final String title = (i >= 0 && i < queue.size()) ? queue.get(i).title : "Capri Player";
        final String artist = (i >= 0 && i < queue.size())
                ? (queue.get(i).artist == null ? "" : queue.get(i).artist) : "";
        final String data = (i >= 0 && i < queue.size()) ? queue.get(i).data : "";
        startForeground(NOTI_ID, noti(title, artist, null));
        new Thread(() -> {
            final android.graphics.Bitmap b = data.isEmpty() ? null : TrackAdapter.getArt(data);
            main.post(() -> {
                if (idx == i) {
                    try {
                        startForeground(NOTI_ID, noti(title, artist, b));
                    } catch (Throwable ignored) {}
                }
            });
        }).start();
    }

    private Notification noti(String title, String text, android.graphics.Bitmap art) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pOpen = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        Intent iPrev = new Intent(this, PlayerService.class).setAction("PREV");
        PendingIntent pPrev = PendingIntent.getService(
                this, 1, iPrev, PendingIntent.FLAG_IMMUTABLE);

        Intent iTo = new Intent(this, PlayerService.class).setAction("TOGGLE");
        PendingIntent pTo = PendingIntent.getService(
                this, 2, iTo, PendingIntent.FLAG_IMMUTABLE);

        Intent iNext = new Intent(this, PlayerService.class).setAction("NEXT");
        PendingIntent pNext = PendingIntent.getService(
                this, 3, iNext, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text.isEmpty() ? "Capri Player" : text)
                .setContentIntent(pOpen)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action(0, "⏮", pPrev))
                .addAction(new Notification.Action(0, isPlaying() ? "⏸" : "▶", pTo))
                .addAction(new Notification.Action(0, "⏭", pNext));
        if (art != null) b.setLargeIcon(art);
        return b.build();
    }

    @Override
    public void onDestroy() {
        try { if (session != null) session.release(); } catch (Throwable ignored) {}
        try { if (mp != null) { mp.stop(); mp.release(); } } catch (Throwable ignored) {}
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            am.abandonAudioFocus(null);
        } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
