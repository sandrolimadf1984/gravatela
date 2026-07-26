package com.sandro.gravatela;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import java.util.Locale;

/**
 * A bola flutuante que fica por cima dos outros apps.
 * Ela arrasta, abre um menuzinho e mostra o tempo de gravacao.
 */
public class BubbleService extends Service {

    public static volatile boolean ATIVA = false;

    private static final String CANAL = "bolha";
    private static final int ID_NOTIF = 1002;

    private WindowManager wm;
    private WindowManager.LayoutParams params;
    private View raiz, menu;
    private TextView bolha, btnPausar, btnParar, btnGravar, btnCasa, btnConfig;
    private boolean aberto = false;

    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        criarCanal();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_NOTIF, montarNotificacao(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ID_NOTIF, montarNotificacao());
        }

        montarBolha();
        ATIVA = true;
        h.post(tique);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    // ------------------------------------------------------------------
    private void montarBolha() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        raiz = LayoutInflater.from(this).inflate(R.layout.bolha, null);

        menu = raiz.findViewById(R.id.menuBolha);
        bolha = raiz.findViewById(R.id.bolha);
        btnPausar = raiz.findViewById(R.id.btnBolhaPausar);
        btnParar = raiz.findViewById(R.id.btnBolhaParar);
        btnGravar = raiz.findViewById(R.id.btnBolhaGravar);
        btnCasa = raiz.findViewById(R.id.btnBolhaCasa);
        btnConfig = raiz.findViewById(R.id.btnBolhaConfig);

        int tipo = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                tipo,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        DisplayMetrics m = new DisplayMetrics();
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m);
        params.x = m.widthPixels - (int) (72 * m.density);
        params.y = m.heightPixels / 3;

        wm.addView(raiz, params);

        bolha.setOnTouchListener(new View.OnTouchListener() {
            int xInicial, yInicial;
            float toqueX, toqueY;
            long momento;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        xInicial = params.x;
                        yInicial = params.y;
                        toqueX = e.getRawX();
                        toqueY = e.getRawY();
                        momento = SystemClock.elapsedRealtime();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = xInicial + (int) (e.getRawX() - toqueX);
                        params.y = yInicial + (int) (e.getRawY() - toqueY);
                        wm.updateViewLayout(raiz, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        boolean parado = Math.abs(e.getRawX() - toqueX) < 16
                                && Math.abs(e.getRawY() - toqueY) < 16;
                        if (parado && SystemClock.elapsedRealtime() - momento < 500) {
                            aberto = !aberto;
                            desenhar();
                        }
                        return true;
                }
                return false;
            }
        });

        btnGravar.setOnClickListener(v -> {
            aberto = false;
            Intent i = new Intent(this, MainActivity.class);
            i.putExtra(MainActivity.EXTRA_INICIAR, true);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            desenhar();
        });

        btnPausar.setOnClickListener(v -> {
            if (!RecorderService.ATIVO) return;
            mandarParaServico(RecorderService.PAUSADO
                    ? RecorderService.ACAO_CONTINUAR
                    : RecorderService.ACAO_PAUSAR);
        });

        btnParar.setOnClickListener(v -> {
            if (!RecorderService.ATIVO) return;
            mandarParaServico(RecorderService.ACAO_PARAR);
            aberto = false;
            desenhar();
        });

        btnCasa.setOnClickListener(v -> {
            aberto = false;
            Intent i = new Intent(this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            desenhar();
        });

        btnConfig.setOnClickListener(v -> {
            aberto = false;
            Intent i = new Intent(this, SettingsActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            desenhar();
        });

        raiz.findViewById(R.id.btnBolhaFechar).setOnClickListener(v -> {
            Prefs.setBolhaLigada(this, false);
            stopSelf();
        });

        desenhar();
    }

    private void mandarParaServico(String acao) {
        Intent i = new Intent(this, RecorderService.class).setAction(acao);
        startService(i);
    }

    // ------------------------------------------------------------------
    private final Runnable tique = new Runnable() {
        @Override
        public void run() {
            desenhar();
            h.postDelayed(this, 500);
        }
    };

    private void desenhar() {
        if (raiz == null) return;
        boolean gravando = RecorderService.ATIVO;

        // some da tela enquanto grava, se o usuario pediu isso nas Definicoes
        boolean esconder = gravando && Prefs.esconderBolha(this);
        raiz.setVisibility(esconder ? View.GONE : View.VISIBLE);

        menu.setVisibility(aberto ? View.VISIBLE : View.GONE);

        if (gravando) {
            bolha.setBackgroundResource(R.drawable.bg_bolha_escura);
            long s = RecorderService.tempoMs() / 1000;
            bolha.setTextSize(13);
            bolha.setText(String.format(Locale.getDefault(), "%02d:%02d", s / 60, s % 60));
            btnPausar.setText(RecorderService.PAUSADO ? "▶" : "❚❚");

            btnPausar.setVisibility(View.VISIBLE);
            btnParar.setVisibility(View.VISIBLE);
            btnGravar.setVisibility(View.GONE);
            btnCasa.setVisibility(View.GONE);
            btnConfig.setVisibility(View.GONE);
        } else {
            bolha.setBackgroundResource(R.drawable.bg_bolha_laranja);
            bolha.setTextSize(18);
            bolha.setText("●");

            btnPausar.setVisibility(View.GONE);
            btnParar.setVisibility(View.GONE);
            btnGravar.setVisibility(View.VISIBLE);
            btnCasa.setVisibility(View.VISIBLE);
            btnConfig.setVisibility(View.VISIBLE);
        }
    }

    // ------------------------------------------------------------------
    private void criarCanal() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CANAL) == null) {
            NotificationChannel canal = new NotificationChannel(
                    CANAL, "Bola flutuante", NotificationManager.IMPORTANCE_MIN);
            canal.setShowBadge(false);
            nm.createNotificationChannel(canal);
        }
    }

    private Notification montarNotificacao() {
        Intent abrir = new Intent(this, MainActivity.class);
        abrir.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 2, abrir,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_video)
                .setContentTitle("Bola flutuante ligada")
                .setContentText("Toque na bola para gravar")
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        h.removeCallbacks(tique);
        ATIVA = false;
        try {
            if (raiz != null && wm != null) wm.removeView(raiz);
        } catch (Exception ignored) {
        }
        raiz = null;
    }
}
