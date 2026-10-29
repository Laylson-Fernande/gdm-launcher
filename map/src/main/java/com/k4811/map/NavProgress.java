package com.k4811.map;

import com.k4811.map.route.Phrases;

import java.util.ArrayList;
import java.util.List;

/**
 * Progresso da navegação do mapa integrado, para outras telas do mesmo processo (a tela inicial do
 * launcher mostra a próxima manobra no bloco do relógio). O {@link MapScreen} publica a cada
 * atualização da rota e limpa quando ela acaba; quem escuta só se registra enquanto está visível.
 * Tudo na thread principal.
 */
public final class NavProgress {
    private NavProgress() {
    }

    /** Próxima manobra e chegada, com os textos já prontos. */
    public static final class State {
        public final Phrases.Shape shape;
        /** "300 m". */
        public final String distance;
        /** Rua ou ação ("Vire à direita"). */
        public final String cue;
        /** "Chegada 21:31 · 16 min · 5,1 km". */
        public final String eta;

        public State(Phrases.Shape shape, String distance, String cue, String eta) {
            this.shape = shape;
            this.distance = distance;
            this.cue = cue;
            this.eta = eta;
        }

        boolean same(State o) {
            return o != null && shape == o.shape && eq(distance, o.distance) && eq(cue, o.cue) && eq(eta, o.eta);
        }

        private static boolean eq(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    public interface Listener {
        /** {@code s} null = sem rota. */
        void onNavProgress(State s);
    }

    private static State current;
    private static final List<Listener> listeners = new ArrayList<>();

    /** Rota atual; null sem navegação. */
    public static State current() {
        return current;
    }

    public static void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    static void publish(State s) {
        if (s == null ? current == null : s.same(current)) return;
        current = s;
        for (int i = listeners.size() - 1; i >= 0; i--) listeners.get(i).onNavProgress(s);
    }

    static void clear() {
        publish(null);
    }
}
