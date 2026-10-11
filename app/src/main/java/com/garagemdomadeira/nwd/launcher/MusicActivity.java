package com.garagemdomadeira.nwd.launcher;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Bitmap;



import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.garagemdomadeira.nwd.launcher.music.AlbumArt;
import com.garagemdomadeira.nwd.launcher.music.MusicClient;
import com.garagemdomadeira.nwd.launcher.music.MusicState;
import com.garagemdomadeira.nwd.launcher.music.MusicTrack;
import com.garagemdomadeira.nwd.launcher.ui.IconView;
import com.garagemdomadeira.nwd.launcher.ui.DockView;
import com.garagemdomadeira.nwd.launcher.ui.Screens;
import com.garagemdomadeira.nwd.launcher.ui.Touch;
import com.garagemdomadeira.nwd.launcher.ui.Ui;



import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Our USB/SD music screen: current track, transport controls and list. */
public class MusicActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService artLoader = Executors.newSingleThreadExecutor();
    private final List<MusicTrack> tracks = new ArrayList<>();

    private MusicClient music;
    private MusicState state = new MusicState();
    private TrackAdapter adapter;
    private boolean seeking;
    private boolean alive = true;
    private int artGen;
    private String artKey;

    private TextView trackView;
    private TextView artistView;
    private TextView albumView;
    private TextView posView;
    private TextView durView;
    private TextView statusView;
    private TextView listTitle;
    private TextView shuffleBtn;
    private TextView repeatBtn;
    private SeekBar seek;
    private IconView playBtn;
    private ImageView artView;
    private IconView artIcon;
    private ListView list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyDesignDensity(this);
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_music);
        Ui.applyFont(findViewById(R.id.music_root));
        Screens.attachDock(this, DockView.Item.MUSIC);
        bindViews();

        music = new MusicClient(this);
        music.setListener(this::render);
        music.connect();
        Touch.install(findViewById(android.R.id.content));
    }

    private void bindViews() {
        ((TextView) findViewById(R.id.music_title_bar)).setTypeface(Ui.semiBold(this));
        IconView back = (IconView) findViewById(R.id.music_back);
        back.set(IconView.Icon.CHEVRON_LEFT, IconView.Style.NONE);
        back.setOnClickListener(v -> finish());

        shuffleBtn = (TextView) findViewById(R.id.music_shuffle);
        repeatBtn = (TextView) findViewById(R.id.music_repeat);
        TextView reload = (TextView) findViewById(R.id.music_reload);
        shuffleBtn.setOnClickListener(v -> music.setShuffle(!state.shuffle));
        repeatBtn.setOnClickListener(v -> music.cycleRepeat());
        reload.setOnClickListener(v -> music.reload());

        trackView = (TextView) findViewById(R.id.music_track);
        artistView = (TextView) findViewById(R.id.music_artist);
        albumView = (TextView) findViewById(R.id.music_album);
        posView = (TextView) findViewById(R.id.music_pos);
        durView = (TextView) findViewById(R.id.music_dur);
        statusView = (TextView) findViewById(R.id.music_status);
        listTitle = (TextView) findViewById(R.id.music_list_title);
        trackView.setTypeface(Ui.semiBold(this));
        seek = (SeekBar) findViewById(R.id.music_seek);
        artView = (ImageView) findViewById(R.id.music_art);
        artIcon = (IconView) findViewById(R.id.music_art_icon);
        artIcon.set(IconView.Icon.MUSIC, IconView.Style.NONE);
        artIcon.setClickable(false);
        artIcon.color(Ui.OUTLINE_STRONG);

        playBtn = (IconView) findViewById(R.id.music_play);
        playBtn.set(IconView.Icon.PLAY, IconView.Style.OUTLINE_CIRCLE);
        ((IconView) findViewById(R.id.music_prev))
            .set(IconView.Icon.SKIP_PREV, IconView.Style.NONE)
            .setOnClickListener(v -> music.previous());
        playBtn.setOnClickListener(v -> music.toggle());
        ((IconView) findViewById(R.id.music_next))
            .set(IconView.Icon.SKIP_NEXT, IconView.Style.NONE)
            .setOnClickListener(v -> music.next());

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || state.durationMs <= 0) return;
                posView.setText(clock((int) (progress / 1000f * state.durationMs)));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seeking = false;
                if (state.durationMs > 0) {
                    music.seek((int) (seekBar.getProgress() / 1000f * state.durationMs));
                }
            }
        });

        list = (ListView) findViewById(R.id.music_list);
        adapter = new TrackAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> music.playIndex(position));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemBars(getWindow());
        if (music != null) music.claimSource();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.hideSystemBars(getWindow());
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Ui.applyDesignDensity(this);
    }

    @Override
    protected void onDestroy() {
        alive = false;
        artGen++;
        artLoader.shutdownNow();
        if (music != null) {
            music.setListener(null);
            music.release();
        }
        super.onDestroy();
    }

    private void render(MusicState s) {
        state = s;
        MusicTrack cur = s.current();
        if (cur == null) {
            trackView.setText(s.loading ? R.string.media_music_searching : R.string.media_music_no_track);
            artistView.setText("");
            albumView.setText("");
            clearArt();
        } else {
            trackView.setText(cur.displayTitle());
            artistView.setText(cur.artist);
            albumView.setText(cur.album);
            bindArt(cur);
        }

        if (!seeking) {
            if (s.durationMs > 0) {
                seek.setProgress(Math.min(1000, (int) (s.positionMs * 1000L / s.durationMs)));
            } else {
                seek.setProgress(0);
            }
            posView.setText(clock(s.positionMs));
            durView.setText(clock(s.durationMs));
        }

        playBtn.set(s.playing ? IconView.Icon.PAUSE : IconView.Icon.PLAY, IconView.Style.OUTLINE_CIRCLE);
        shuffleBtn.setActivated(s.shuffle);
        repeatBtn.setActivated(s.repeat != MusicState.Repeat.OFF);
        if (s.repeat == MusicState.Repeat.ONE) repeatBtn.setText(R.string.media_music_repeat_one);
        else if (s.repeat == MusicState.Repeat.ALL) repeatBtn.setText(R.string.media_music_repeat_all);
        else repeatBtn.setText(R.string.media_music_repeat);

        if (s.loading) statusView.setText(R.string.media_music_reading);
        else if (s.error != 0) statusView.setText(s.error);
        else if (s.tracks.isEmpty()) statusView.setText(R.string.media_music_empty_hint);
        else statusView.setText(getResources().getQuantityString(R.plurals.media_music_track_count, s.tracks.size(), s.tracks.size()));

        listTitle.setText(getResources().getQuantityString(R.plurals.media_music_list_title,
            s.tracks.size(), s.tracks.size()));
        boolean sameList = tracks.size() == s.tracks.size()
            && (tracks.isEmpty() || tracks.get(0) == s.tracks.get(0));
        if (!sameList) {
            tracks.clear();
            tracks.addAll(s.tracks);
        }
        adapter.notifyDataSetChanged();
        if (s.index >= 0 && s.index < tracks.size()) {
            int first = list.getFirstVisiblePosition();
            int last = list.getLastVisiblePosition();
            if (s.index < first || s.index > last) {
                list.setSelection(Math.max(0, s.index - 2));
            }
        }
    }

    private void bindArt(MusicTrack track) {
        String key = track.mediaId > 0 ? "id:" + track.mediaId : "path:" + track.path;
        if (key.equals(artKey)) return;
        artKey = key;
        int gen = ++artGen;
        clearArt();
        artLoader.execute(() -> {
            Bitmap bmp = AlbumArt.decode(this, track.mediaId, track.path);
            handler.post(() -> {
                if (!alive || gen != artGen || bmp == null) return;
                artView.setImageBitmap(bmp);
                artView.setVisibility(View.VISIBLE);
                artIcon.setVisibility(View.GONE);
            });
        });
    }

    private void clearArt() {
        artView.setImageDrawable(null);
        artView.setVisibility(View.GONE);
        artIcon.setVisibility(View.VISIBLE);
        artKey = null;
    }

    private static String clock(int ms) {
        int s = Math.max(0, ms) / 1000;
        if (s >= 3600) {
            return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
        }
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private final class TrackAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return tracks.size();
        }

        @Override
        public MusicTrack getItem(int position) {
            return tracks.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(MusicActivity.this).inflate(R.layout.music_row, parent, false);
                Touch.hapticOnly(row);
                Ui.applyFont(row);
            }
            MusicTrack t = tracks.get(position);
            TextView title = (TextView) row.findViewById(R.id.row_title);
            TextView sub = (TextView) row.findViewById(R.id.row_sub);
            title.setText(t.displayTitle());
            String who = t.artist;
            if (!t.album.isEmpty()) {
                who = who.isEmpty() ? t.album : who + " · " + t.album;
            }
            sub.setText(who);
            boolean current = position == state.index;
            title.setTextColor(current ? Ui.TEXT : Ui.TEXT_SOFT);
            row.setActivated(current);
            return row;
        }
    }
}
