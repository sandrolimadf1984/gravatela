package com.sandro.gravatela;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Guarda as escolhas do usuario (qualidade, som, limite de tempo, bolha).
 * A tela de Definicoes so escreve aqui dentro.
 */
public class Prefs {

    private static final String ARQUIVO = "gravatela";

    public static final String QUALIDADE = "qualidade";        // "alta", "media", "baixa"
    public static final String SOM_INTERNO = "som_interno";    // true / false
    public static final String LIMITE_MIN = "limite_min";      // minutos por arquivo
    public static final String BOLHA_LIGADA = "bolha_ligada";  // true / false
    public static final String ESCONDER_BOLHA = "esconder_bolha"; // some enquanto grava

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE);
    }

    public static String qualidade(Context c) {
        return p(c).getString(QUALIDADE, "alta");
    }

    public static void setQualidade(Context c, String valor) {
        p(c).edit().putString(QUALIDADE, valor).apply();
    }

    public static boolean somInterno(Context c) {
        return p(c).getBoolean(SOM_INTERNO, true);
    }

    public static void setSomInterno(Context c, boolean valor) {
        p(c).edit().putBoolean(SOM_INTERNO, valor).apply();
    }

    /** Minutos por arquivo. Passou disso, ele salva e comeca outro sozinho. */
    public static int limiteMinutos(Context c) {
        return p(c).getInt(LIMITE_MIN, 60);
    }

    public static void setLimiteMinutos(Context c, int valor) {
        p(c).edit().putInt(LIMITE_MIN, valor).apply();
    }

    public static boolean bolhaLigada(Context c) {
        return p(c).getBoolean(BOLHA_LIGADA, false);
    }

    public static void setBolhaLigada(Context c, boolean valor) {
        p(c).edit().putBoolean(BOLHA_LIGADA, valor).apply();
    }

    public static boolean esconderBolha(Context c) {
        return p(c).getBoolean(ESCONDER_BOLHA, false);
    }

    public static void setEsconderBolha(Context c, boolean valor) {
        p(c).edit().putBoolean(ESCONDER_BOLHA, valor).apply();
    }

    /** Menor lado da imagem: 1080p, 720p ou 480p. */
    public static int ladoMenor(Context c) {
        switch (qualidade(c)) {
            case "baixa": return 480;
            case "media": return 720;
            default: return 1080;
        }
    }

    /** Taxa de bits do video (quanto maior, melhor a imagem e maior o arquivo). */
    public static int bitrate(Context c) {
        switch (qualidade(c)) {
            case "baixa": return 2_500_000;
            case "media": return 6_000_000;
            default: return 12_000_000;
        }
    }

    public static int fps(Context c) {
        return 30;
    }
}
