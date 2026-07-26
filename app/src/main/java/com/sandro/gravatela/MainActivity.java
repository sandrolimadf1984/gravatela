package com.sandro.gravatela;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_INICIAR = "iniciar_gravacao";

    private TextView btnGravar, txtTempo, btnPausar;
    private View linhaGravacao;

    private final Handler relogio = new Handler(Looper.getMainLooper());
    private boolean veioDaBolha = false;

    /** Pede as permissoes de audio e notificacao. */
    private final ActivityResultLauncher<String[]> pedirPermissoes =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    resultado -> pedirCaptura());

    /** Abre a caixa do Android "Iniciar gravacao com GravaTela?". */
    private final ActivityResultLauncher<Intent> pedirTela =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                    Intent i = new Intent(this, RecorderService.class);
                    i.setAction(RecorderService.ACAO_INICIAR);
                    i.putExtra(RecorderService.EXTRA_CODIGO, r.getResultCode());
                    i.putExtra(RecorderService.EXTRA_DADOS, r.getData());
                    ContextCompat.startForegroundService(this, i);
                    if (veioDaBolha) {
                        veioDaBolha = false;
                        relogio.postDelayed(() -> moveTaskToBack(true), 400);
                    }
                } else {
                    aviso("Você precisa tocar em \"Iniciar agora\" para gravar");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets barras = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(barras.left, barras.top, barras.right, barras.bottom);
            return insets;
        });

        btnGravar = findViewById(R.id.btnGravar);
        txtTempo = findViewById(R.id.txtTempo);
        btnPausar = findViewById(R.id.btnPausar);
        linhaGravacao = findViewById(R.id.linhaGravacao);

        mostrarArmazenamento();

        btnGravar.setOnClickListener(v -> {
            if (RecorderService.ATIVO) {
                enviar(RecorderService.ACAO_PARAR);
            } else {
                comecar();
            }
        });

        btnPausar.setOnClickListener(v -> {
            if (!RecorderService.ATIVO) return;
            enviar(RecorderService.PAUSADO
                    ? RecorderService.ACAO_CONTINUAR
                    : RecorderService.ACAO_PAUSAR);
        });

        findViewById(R.id.boxBolha).setOnClickListener(v -> alternarBolha());
        findViewById(R.id.boxVideos).setOnClickListener(v -> abrirGaleria());
        findViewById(R.id.navVideos).setOnClickListener(v -> abrirGaleria());
        findViewById(R.id.boxConfig).setOnClickListener(v -> abrirDefinicoes());
        findViewById(R.id.btnConfigTopo).setOnClickListener(v -> abrirDefinicoes());
        findViewById(R.id.navConfig).setOnClickListener(v -> abrirDefinicoes());
        findViewById(R.id.navVideo).setOnClickListener(v -> aviso("Você já está na aba Vídeo"));

        conferirPedidoDaBolha(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        conferirPedidoDaBolha(intent);
    }

    /** Quando a gravacao e pedida pela bola flutuante. */
    private void conferirPedidoDaBolha(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_INICIAR, false)) {
            intent.removeExtra(EXTRA_INICIAR);
            veioDaBolha = true;
            if (!RecorderService.ATIVO) comecar();
        }
    }

    private void abrirDefinicoes() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    /** Liga ou desliga a bola flutuante. */
    private void alternarBolha() {
        boolean ligar = !Prefs.bolhaLigada(this);
        if (ligar && !Settings.canDrawOverlays(this)) {
            aviso("Ligue \"Aparecer sobre outros apps\" e volte ao app");
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        Prefs.setBolhaLigada(this, ligar);
        Intent i = new Intent(this, BubbleService.class);
        if (ligar) {
            ContextCompat.startForegroundService(this, i);
            aviso("Bola flutuante ligada");
        } else {
            stopService(i);
            aviso("Bola flutuante desligada");
        }
    }

    // ------------------------------------------------------------------
    // COMECAR A GRAVAR
    // ------------------------------------------------------------------
    private void comecar() {
        List<String> faltando = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            faltando.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            faltando.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!faltando.isEmpty()) {
            pedirPermissoes.launch(faltando.toArray(new String[0]));
            return;
        }
        pedirCaptura();
    }

    private void pedirCaptura() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        pedirTela.launch(mpm.createScreenCaptureIntent());
    }

    private void enviar(String acao) {
        Intent i = new Intent(this, RecorderService.class);
        i.setAction(acao);
        startService(i);
    }

    private void abrirGaleria() {
        startActivity(new Intent(this, VideosActivity.class));
    }

    // ------------------------------------------------------------------
    // ATUALIZAR A TELA ENQUANTO GRAVA
    // ------------------------------------------------------------------
    private final Runnable tique = new Runnable() {
        @Override
        public void run() {
            atualizarTela();
            relogio.postDelayed(this, 500);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        relogio.post(tique);
    }

    @Override
    protected void onPause() {
        super.onPause();
        relogio.removeCallbacks(tique);
    }

    private void atualizarTela() {
        boolean gravando = RecorderService.ATIVO;
        linhaGravacao.setVisibility(gravando ? View.VISIBLE : View.GONE);

        if (gravando) {
            btnGravar.setText("■  Parar");
            btnGravar.setBackgroundResource(R.drawable.bg_botao_parar);
            btnPausar.setText(RecorderService.PAUSADO ? "▶  Continuar" : "❚❚  Pausar");
            long ms = RecorderService.tempoMs();
            long s = ms / 1000;
            txtTempo.setText(String.format(Locale.getDefault(),
                    "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60));
        } else {
            btnGravar.setText("●  Gravar");
            btnGravar.setBackgroundResource(R.drawable.bg_botao_gravar);
        }
    }

    private void aviso(String mensagem) {
        Toast.makeText(this, mensagem, Toast.LENGTH_SHORT).show();
    }

    /** Mostra quanto espaco livre o celular tem, igual ao contador do XRecorder. */
    private void mostrarArmazenamento() {
        TextView chip = findViewById(R.id.txtArmazenamento);
        try {
            StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getPath());
            double gb = 1024.0 * 1024.0 * 1024.0;
            double total = (stat.getBlockCountLong() * stat.getBlockSizeLong()) / gb;
            double livre = (stat.getAvailableBlocksLong() * stat.getBlockSizeLong()) / gb;
            chip.setText(String.format(Locale.getDefault(), "%.1f/%.1f GB livres", livre, total));
        } catch (Exception e) {
            chip.setText("armazenamento");
        }
    }
}
