package com.sandro.gravatela;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

/**
 * Servico que segura a gravacao. Ele fica em primeiro plano (com notificacao),
 * senao o Android mata a gravacao quando voce sai do app.
 */
public class RecorderService extends Service {

    public static final String ACAO_INICIAR = "com.sandro.gravatela.INICIAR";
    public static final String ACAO_PAUSAR = "com.sandro.gravatela.PAUSAR";
    public static final String ACAO_CONTINUAR = "com.sandro.gravatela.CONTINUAR";
    public static final String ACAO_PARAR = "com.sandro.gravatela.PARAR";

    public static final String EXTRA_CODIGO = "codigo";
    public static final String EXTRA_DADOS = "dados";

    // estado que a tela principal le para se atualizar
    public static volatile boolean ATIVO = false;
    public static volatile boolean PAUSADO = false;

    private static final String CANAL = "gravacao";
    private static final int ID_NOTIF = 1001;
    private static final String TAG = "GravaTela";

    private static long inicioMs = 0;
    private static long pausadoAcumMs = 0;
    private static long pausaInicioMs = 0;

    private MediaProjection projecao;
    private ScreenRecorder gravador;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Tempo de gravacao ja descontando as pausas. */
    public static long tempoMs() {
        if (!ATIVO) return 0;
        long agora = SystemClock.elapsedRealtime();
        long pausa = pausadoAcumMs + (PAUSADO ? (agora - pausaInicioMs) : 0);
        return Math.max(0, agora - inicioMs - pausa);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String acao = intent == null ? null : intent.getAction();
        if (acao == null) return START_NOT_STICKY;

        switch (acao) {
            case ACAO_INICIAR:
                iniciar(intent);
                break;
            case ACAO_PAUSAR:
                if (gravador != null && ATIVO && !PAUSADO) {
                    gravador.pausar();
                    PAUSADO = true;
                    pausaInicioMs = SystemClock.elapsedRealtime();
                    atualizarNotificacao();
                }
                break;
            case ACAO_CONTINUAR:
                if (gravador != null && ATIVO && PAUSADO) {
                    gravador.continuar();
                    pausadoAcumMs += SystemClock.elapsedRealtime() - pausaInicioMs;
                    PAUSADO = false;
                    atualizarNotificacao();
                }
                break;
            case ACAO_PARAR:
                parar();
                break;
        }
        return START_NOT_STICKY;
    }

    private void iniciar(Intent intent) {
        if (ATIVO) return;

        criarCanal();
        startForeground(ID_NOTIF, montarNotificacao(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        int codigo = intent.getIntExtra(EXTRA_CODIGO, 0);
        Intent dados = intent.getParcelableExtra(EXTRA_DADOS);
        if (dados == null) {
            aviso("Não consegui a permissão de captura");
            pararTudo();
            return;
        }

        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projecao = mpm.getMediaProjection(codigo, dados);

            // Obrigatorio registrar antes de criar o display virtual
            projecao.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    parar();
                }
            }, mainHandler);

            gravador = new ScreenRecorder(this, projecao);
            gravador.iniciar();

            ATIVO = true;
            PAUSADO = false;
            inicioMs = SystemClock.elapsedRealtime();
            pausadoAcumMs = 0;
            atualizarNotificacao();
        } catch (Exception e) {
            Log.e(TAG, "erro ao iniciar", e);
            aviso("Erro ao iniciar a gravação: " + e.getMessage());
            pararTudo();
        }
    }

    private void parar() {
        if (!ATIVO) {
            pararTudo();
            return;
        }
        ATIVO = false;
        PAUSADO = false;
        try {
            if (gravador != null) gravador.parar();
        } catch (Exception e) {
            Log.e(TAG, "erro ao parar", e);
        }
        try {
            if (projecao != null) projecao.stop();
        } catch (Exception ignored) {
        }
        gravador = null;
        projecao = null;
        aviso("Vídeo salvo na galeria");
        pararTudo();
    }

    private void pararTudo() {
        ATIVO = false;
        PAUSADO = false;
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (gravador != null) {
            try {
                gravador.parar();
            } catch (Exception ignored) {
            }
            gravador = null;
        }
        ATIVO = false;
        PAUSADO = false;
    }

    // ------------------------------------------------------------------
    // NOTIFICACAO
    // ------------------------------------------------------------------
    private void criarCanal() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CANAL) == null) {
            NotificationChannel canal = new NotificationChannel(
                    CANAL, "Gravação de tela", NotificationManager.IMPORTANCE_LOW);
            canal.setShowBadge(false);
            nm.createNotificationChannel(canal);
        }
    }

    private PendingIntent acao(String nomeAcao) {
        Intent i = new Intent(this, RecorderService.class).setAction(nomeAcao);
        return PendingIntent.getService(this, nomeAcao.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification montarNotificacao() {
        Intent abrir = new Intent(this, MainActivity.class);
        abrir.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent piAbrir = PendingIntent.getActivity(this, 0, abrir,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_video)
                .setContentTitle("GravaTela")
                .setContentText(PAUSADO ? "Gravação pausada" : "Gravando a tela…")
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(piAbrir);

        if (PAUSADO) {
            b.addAction(0, "Continuar", acao(ACAO_CONTINUAR));
        } else {
            b.addAction(0, "Pausar", acao(ACAO_PAUSAR));
        }
        b.addAction(0, "Parar", acao(ACAO_PARAR));
        return b.build();
    }

    private void atualizarNotificacao() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(ID_NOTIF, montarNotificacao());
    }

    private void aviso(String texto) {
        mainHandler.post(() -> Toast.makeText(this, texto, Toast.LENGTH_LONG).show());
    }
}
