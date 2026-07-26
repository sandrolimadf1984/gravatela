package com.sandro.gravatela;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.raizConfig), (v, insets) -> {
            Insets barras = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(barras.left, barras.top, barras.right, barras.bottom);
            return insets;
        });

        findViewById(R.id.btnVoltar).setOnClickListener(v -> finish());

        // qualidade
        findViewById(R.id.optAlta).setOnClickListener(v -> escolherQualidade("alta"));
        findViewById(R.id.optMedia).setOnClickListener(v -> escolherQualidade("media"));
        findViewById(R.id.optBaixa).setOnClickListener(v -> escolherQualidade("baixa"));

        // som
        findViewById(R.id.optSom).setOnClickListener(v -> {
            Prefs.setSomInterno(this, !Prefs.somInterno(this));
            desenhar();
        });

        // tempo
        findViewById(R.id.opt30).setOnClickListener(v -> escolherLimite(30));
        findViewById(R.id.opt60).setOnClickListener(v -> escolherLimite(60));
        findViewById(R.id.opt120).setOnClickListener(v -> escolherLimite(120));

        // bolha
        findViewById(R.id.optBolha).setOnClickListener(v -> alternarBolha());
        findViewById(R.id.optEsconder).setOnClickListener(v -> {
            Prefs.setEsconderBolha(this, !Prefs.esconderBolha(this));
            desenhar();
        });

        desenhar();
    }

    @Override
    protected void onResume() {
        super.onResume();
        desenhar();
    }

    private void escolherQualidade(String valor) {
        if (RecorderService.ATIVO) {
            aviso("Pare a gravação antes de mudar a qualidade");
            return;
        }
        Prefs.setQualidade(this, valor);
        desenhar();
    }

    private void escolherLimite(int minutos) {
        if (RecorderService.ATIVO) {
            aviso("Pare a gravação antes de mudar o tempo");
            return;
        }
        Prefs.setLimiteMinutos(this, minutos);
        desenhar();
    }

    private void alternarBolha() {
        boolean ligar = !Prefs.bolhaLigada(this);
        if (ligar && !Settings.canDrawOverlays(this)) {
            aviso("Ligue \"Aparecer sobre outros apps\" e volte aqui");
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        Prefs.setBolhaLigada(this, ligar);
        Intent i = new Intent(this, BubbleService.class);
        if (ligar) {
            ContextCompat.startForegroundService(this, i);
        } else {
            stopService(i);
        }
        desenhar();
    }

    /** Redesenha as marcas de selecionado / ligado. */
    private void desenhar() {
        String q = Prefs.qualidade(this);
        marcar(R.id.optAlta, "Alta — 1080p", q.equals("alta"));
        marcar(R.id.optMedia, "Média — 720p", q.equals("media"));
        marcar(R.id.optBaixa, "Baixa — 480p", q.equals("baixa"));

        marcar(R.id.optSom, "Gravar som interno", Prefs.somInterno(this));

        int lim = Prefs.limiteMinutos(this);
        marcar(R.id.opt30, "30 minutos", lim == 30);
        marcar(R.id.opt60, "1 hora", lim == 60);
        marcar(R.id.opt120, "2 horas", lim == 120);

        marcar(R.id.optBolha, "Mostrar a bola flutuante", Prefs.bolhaLigada(this));
        marcar(R.id.optEsconder, "Esconder a bola durante a gravação", Prefs.esconderBolha(this));
    }

    private void marcar(int id, String texto, boolean ligado) {
        TextView t = findViewById(id);
        t.setText(ligado ? texto + "     ✓" : texto);
        t.setTextColor(ligado ? 0xFFFF6D00 : 0xFF1C1C1E);
    }

    private void aviso(String mensagem) {
        Toast.makeText(this, mensagem, Toast.LENGTH_SHORT).show();
    }
}
