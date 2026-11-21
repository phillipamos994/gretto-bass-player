package com.example.gretto;

import android.content.Intent;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    // ---------- Updates / API ----------
    private static final String API_KEY = "amos3681@0000";
    private static final String UPDATE_URL = "https://your-server.com/api/updates";

    // ---------- UI ----------
    private ListView listView;
    private SurfaceView videoSurface;
    private SurfaceHolder surfaceHolder;
    private SeekBar seekBar, volBar;
    private TextView timeLabel, volLabel, statusLabel;

    // ---------- State ----------
    private final List<Uri> playlist = new ArrayList<>();
    private final List<String> trackNames = new ArrayList<>();
    private ArrayAdapter<String> listAdapter;

    private MediaPlayer mediaPlayer;
    private int currentIdx = -1;
    private int selectedIdx = -1;
    private boolean isOpened = false;
    private boolean isDragging = false;
    private int volume = 50; // 0..100

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    // ---------- File Picker (Replaces GetOpenFileNameW) ----------
    private final ActivityResultLauncher<String[]> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris != null && !uris.isEmpty()) {
                    for (Uri uri : uris) {
                        try {
                            getContentResolver().takePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (SecurityException ignored) {}
                    }
                    addFiles(uris);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupListeners();
        handleIncomingIntent(getIntent());

        // Start 300ms UI update timer (replaces SetTimer)
        timerHandler.postDelayed(updateTimerRunnable, 300);
        checkUpdates();
    }

    private void initViews() {
        listView = findViewById(R.id.list_playlist);
        videoSurface = findViewById(R.id.surface_video);
        seekBar = findViewById(R.id.seek_bar);
        timeLabel = findViewById(R.id.txt_time);
        volLabel = findViewById(R.id.txt_vol_label);
        volBar = findViewById(R.id.seek_volume);
        statusLabel = findViewById(R.id.txt_status);

        surfaceHolder = videoSurface.getHolder();
        surfaceHolder.addCallback(this);

        listAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_activated_1, trackNames);
        listView.setAdapter(listAdapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        volBar.setMax(100);
        volBar.setProgress(volume);
        volLabel.setText("Vol " + volume + "%");
    }

    private void setupListeners() {
        findViewById(R.id.btn_open).setOnClickListener(v ->
                filePickerLauncher.launch(new String[]{"audio/*", "video/*"}));
        findViewById(R.id.btn_prev).setOnClickListener(v -> doPrev());
        findViewById(R.id.btn_rew).setOnClickListener(v -> {
            if (isOpened) seekTo(mediaPlayer.getCurrentPosition() - 10000);
        });
        findViewById(R.id.btn_play).setOnClickListener(v -> doPlay());
        findViewById(R.id.btn_pause).setOnClickListener(v -> doPause());
        findViewById(R.id.btn_stop).setOnClickListener(v -> doStop());
        findViewById(R.id.btn_fwd).setOnClickListener(v -> {
            if (isOpened) seekTo(mediaPlayer.getCurrentPosition() + 10000);
        });
        findViewById(R.id.btn_next).setOnClickListener(v -> doNext());
        findViewById(R.id.btn_remove).setOnClickListener(v -> doRemove());
        findViewById(R.id.btn_update).setOnClickListener(v -> checkUpdates());

        // Single tap selects; tapping an already-selected item plays it
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (selectedIdx == position) {
                loadTrack(position, true);
            } else {
                selectedIdx = position;
                listView.setItemChecked(position, true);
            }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && isOpened) {
                    updateTimeLabel(progress, mediaPlayer.getDuration());
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                isDragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                isDragging = false;
                if (isOpened) seekTo(bar.getProgress());
            }
        });

        volBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                volume = progress;
                applyVolume();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
    }

    // ---------- Helpers ----------
    private String fmtTime(int ms) {
        if (ms < 0) ms = 0;
        int s = ms / 1000;
        if (s >= 3600) {
            return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        }
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) result = cursor.getString(idx);
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result != null ? result.lastIndexOf('/') : -1;
            if (cut != -1) result = result.substring(cut + 1);
        }
        return result != null ? result : "Unknown media";
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private void applyVolume() {
        volLabel.setText("Vol " + volume + "%");
        if (isOpened && mediaPlayer != null) {
            float scalar = volume / 100f;
            mediaPlayer.setVolume(scalar, scalar);
        }
    }

    private void updateTimeLabel(int pos, int length) {
        timeLabel.setText(fmtTime(pos) + " / " + fmtTime(length));
    }

    // ---------- Playback ----------
    private void closeMedia() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        isOpened = false;
        seekBar.setProgress(0);
        updateTimeLabel(0, 0);
    }

    private void loadTrack(int idx, boolean autoplay) {
        if (idx < 0 || idx >= playlist.size()) return;
        closeMedia();

        currentIdx = idx;
        selectedIdx = idx;
        listView.setItemChecked(idx, true);
        listView.setSelection(idx);

        Uri uri = playlist.get(idx);
        String name = trackNames.get(idx);

        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, uri);
            if (surfaceHolder.getSurface().isValid()) {
                mediaPlayer.setDisplay(surfaceHolder);
            }
            mediaPlayer.setOnPreparedListener(mp -> {
                isOpened = true;
                int length = mp.getDuration();
                seekBar.setMax(Math.max(length, 1));
                applyVolume();
                updateTimeLabel(0, length);
                if (autoplay) {
                    mp.start();
                    setStatus("Playing: " + name);
                } else {
                    setStatus("Loaded: " + name);
                }
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                if (currentIdx + 1 < playlist.size()) {
                    loadTrack(currentIdx + 1, true);
                } else {
                    setStatus("Finished.");
                }
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                setStatus("Cannot play: " + name);
                closeMedia();
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            setStatus("Cannot play: " + name);
            closeMedia();
        }
    }

    private void doPlay() {
        if (playlist.isEmpty()) {
            setStatus("No media loaded to play. Tap Open.");
            return;
        }
        if (!isOpened || mediaPlayer == null) {
            loadTrack(currentIdx < 0 ? 0 : currentIdx, true);
            return;
        }
        if (!mediaPlayer.isPlaying()) {
            mediaPlayer.start();
            setStatus("Playing: " + trackNames.get(currentIdx));
        }
    }

    private void doPause() {
        if (isOpened && mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            setStatus("Paused: " + trackNames.get(currentIdx));
        } else {
            setStatus("Already paused or stopped.");
        }
    }

    private void doStop() {
        if (!isOpened || mediaPlayer == null) return;
        if (mediaPlayer.isPlaying()) mediaPlayer.pause();
        mediaPlayer.seekTo(0);
        seekBar.setProgress(0);
        updateTimeLabel(0, mediaPlayer.getDuration());
        setStatus("Stopped.");
    }

    private void doNext() {
        if (playlist.isEmpty()) {
            setStatus("Playlist is empty.");
            return;
        }
        int next = (currentIdx + 1) % playlist.size();
        loadTrack(next, true);
    }

    private void doPrev() {
        if (playlist.isEmpty()) {
            setStatus("Playlist is empty.");
            return;
        }
        int prev = (currentIdx - 1 + playlist.size()) % playlist.size();
        loadTrack(prev, true);
    }

    private void seekTo(int ms) {
        if (!isOpened || mediaPlayer == null) return;
        int clamped = Math.max(0, Math.min(ms, mediaPlayer.getDuration()));
        mediaPlayer.seekTo(clamped);
        seekBar.setProgress(clamped);
        updateTimeLabel(clamped, mediaPlayer.getDuration());
    }

    private void addFiles(List<Uri> uris) {
        boolean wasEmpty = playlist.isEmpty();
        for (Uri u : uris) {
            playlist.add(u);
            trackNames.add(getFileName(u));
        }
        listAdapter.notifyDataSetChanged();
        if (!uris.isEmpty()) {
            setStatus("Added " + uris.size() + " file(s) to the playlist.");
        }
        if (wasEmpty && !uris.isEmpty()) {
            loadTrack(0, false);
        }
    }

    private void doRemove() {
        if (selectedIdx < 0 || selectedIdx >= playlist.size()) return;
        boolean wasCurrent = (selectedIdx == currentIdx);
        playlist.remove(selectedIdx);
        trackNames.remove(selectedIdx);
        listAdapter.notifyDataSetChanged();

        if (wasCurrent) {
            closeMedia();
            currentIdx = -1;
            setStatus("Removed.");
        } else if (selectedIdx < currentIdx) {
            currentIdx--;
        }
        selectedIdx = -1;
        listView.clearChoices();
    }

    // ---------- Timer & Network ----------
    private final Runnable updateTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isOpened && mediaPlayer != null && !isDragging) {
                try {
                    int pos = mediaPlayer.getCurrentPosition();
                    int len = mediaPlayer.getDuration();
                    seekBar.setProgress(pos);
                    updateTimeLabel(pos, len);
                } catch (IllegalStateException ignored) {}
            }
            timerHandler.postDelayed(this, 300);
        }
    };

    private void checkUpdates() {
        setStatus("[UPDATES] Checking with API key...");
        networkExecutor.execute(() -> {
            String result = "[UPDATES] Could not reach the update server.";
            try {
                URL url = new URL(UPDATE_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "gretto/1.0");
                conn.setRequestProperty("X-API-Key", API_KEY);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null && sb.length() < 200) {
                        sb.append(line);
                    }
                    reader.close();
                    String body = sb.toString().trim();
                    if (body.length() > 200) body = body.substring(0, 200);
                    result = "[UPDATES] " + (body.isEmpty() ? "No new updates." : body);
                }
                conn.disconnect();
            } catch (Exception ignored) {}

            final String finalMsg = result;
            runOnUiThread(() -> setStatus(finalMsg));
        });
    }

    // ---------- "Open With" Intent Handling ----------
    private void handleIncomingIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            List<Uri> files = new ArrayList<>();
            files.add(intent.getData());
            addFiles(files);
            loadTrack(playlist.size() - 1, true);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    // ---------- SurfaceHolder Callbacks ----------
    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        if (mediaPlayer != null) mediaPlayer.setDisplay(holder);
    }
    @Override public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}
    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        if (mediaPlayer != null) mediaPlayer.setDisplay(null);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isOpened && mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            setStatus("Paused: " + trackNames.get(currentIdx));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        timerHandler.removeCallbacks(updateTimerRunnable);
        networkExecutor.shutdownNow();
        closeMedia();
    }
}