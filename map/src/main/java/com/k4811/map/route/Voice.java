package com.k4811.map.route;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;

/**
 * Instruções por voz com o TextToSpeech do Android. Fala como "orientação de navegação" e pede o
 * foco de áudio temporário com "pode abaixar": a música baixa (não pausa) enquanto fala, como no Waze.
 * Sem motor de voz instalado, fica mudo.
 */
public final class Voice {

    private static final String TAG = "K4811Map";

    private final AudioManager audio;
    private TextToSpeech tts;
    private boolean ready;
    private boolean enabled = true;
    /** Id da última frase pedida: o foco do áudio só é devolvido quando ela termina. */
    private volatile String lastId = "";
    private int seq;
    private final AudioManager.OnAudioFocusChangeListener focus = change -> {
    };

    public Voice(Context c, Locale locale) {
        Context app = c.getApplicationContext();
        audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        try {
            tts = new TextToSpeech(app, status -> {
                if (status != TextToSpeech.SUCCESS || tts == null) return;
                int r = tts.setLanguage(locale);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "voz sem " + locale + "; usando a padrão");
                }
                if (Build.VERSION.SDK_INT >= 21) navigationAttributes();
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {
                    }

                    @Override
                    public void onDone(String id) {
                        if (id.equals(lastId)) release();
                    }

                    @Override
                    public void onError(String id) {
                        if (id.equals(lastId)) release();
                    }
                });
                ready = true;
            });
        } catch (RuntimeException e) {
            Log.w(TAG, "TextToSpeech: " + e);
        }
    }

    public void setEnabled(boolean on) {
        enabled = on;
        if (!on && tts != null) tts.stop();
    }

    /** Fala agora, interrompendo o que estiver falando (manobras). */
    public void say(String text) {
        speak(text, TextToSpeech.QUEUE_FLUSH);
    }

    /** Fala depois do que estiver falando (alertas: não cortam uma manobra). */
    public void sayAfter(String text) {
        speak(text, TextToSpeech.QUEUE_ADD);
    }

    @SuppressWarnings("deprecation")
    private void speak(String text, int mode) {
        if (!enabled || !ready || tts == null || text == null || text.isEmpty()) return;
        audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        String id = "nav" + (++seq);
        lastId = id;
        if (Build.VERSION.SDK_INT >= 21) speak21(text, mode, id);
        else {
            java.util.HashMap<String, String> p = new java.util.HashMap<>();
            p.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id);
            tts.speak(text, mode, p);
        }
    }

    @TargetApi(21)
    private void speak21(String text, int mode, String id) {
        tts.speak(text, mode, new Bundle(), id);
    }

    @TargetApi(21)
    private void navigationAttributes() {
        tts.setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build());
    }

    @SuppressWarnings("deprecation")
    private void release() {
        audio.abandonAudioFocus(focus);
    }

    public void shutdown() {
        ready = false;
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        release();
    }
}
