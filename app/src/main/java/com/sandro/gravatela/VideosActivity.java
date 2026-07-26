package com.sandro.gravatela;

import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lista as gravacoes que estao em Movies/GravaTela. */
public class VideosActivity extends AppCompatActivity {

    static class Video {
        long id;
        Uri uri;
        String nome;
        long duracaoMs;
        long bytes;
        long dataSeg;
    }

    private final List<Video> lista = new ArrayList<>();
    private final ExecutorService fila = Executors.newFixedThreadPool(2);
    private final Handler principal = new Handler(Looper.getMainLooper());
    private Adaptador adaptador;
    private TextView vazio;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_videos);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.raizVideos), (v, insets) -> {
            Insets barras = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(barras.left, barras.top, barras.right, barras.bottom);
            return insets;
        });

        findViewById(R.id.btnVoltarVideos).setOnClickListener(v -> finish());

        vazio = findViewById(R.id.txtVazio);
        ListView listView = findViewById(R.id.listaVideos);
        adaptador = new Adaptador();
        listView.setAdapter(adaptador);

        listView.setOnItemClickListener((p, v, pos, id) -> abrir(lista.get(pos)));
        listView.setOnItemLongClickListener((p, v, pos, id) -> {
            opcoes(lista.get(pos));
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        carregar();
    }

    // ------------------------------------------------------------------
    private void carregar() {
        lista.clear();
        String[] colunas = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED
        };
        String filtro = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
        String[] valores = {"Movies/GravaTela%"};

        try (Cursor c = getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, colunas, filtro, valores,
                MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    Video v = new Video();
                    v.id = c.getLong(0);
                    v.nome = c.getString(1);
                    v.duracaoMs = c.getLong(2);
                    v.bytes = c.getLong(3);
                    v.dataSeg = c.getLong(4);
                    v.uri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v.id);
                    lista.add(v);
                }
            }
        } catch (Exception e) {
            aviso("Não consegui ler a pasta de vídeos");
        }

        vazio.setVisibility(lista.isEmpty() ? View.VISIBLE : View.GONE);
        adaptador.notifyDataSetChanged();
    }

    private void abrir(Video v) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(v.uri, "video/mp4");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            aviso("Nenhum player de vídeo encontrado");
        }
    }

    private void opcoes(Video v) {
        String[] itens = {"Renomear", "Compartilhar", "Excluir"};
        new AlertDialog.Builder(this)
                .setTitle(v.nome)
                .setItems(itens, (d, qual) -> {
                    if (qual == 0) renomear(v);
                    else if (qual == 1) compartilhar(v);
                    else excluir(v);
                })
                .show();
    }

    private void renomear(Video v) {
        EditText campo = new EditText(this);
        campo.setInputType(InputType.TYPE_CLASS_TEXT);
        String semExtensao = v.nome.endsWith(".mp4")
                ? v.nome.substring(0, v.nome.length() - 4) : v.nome;
        campo.setText(semExtensao);
        campo.setSelection(campo.getText().length());

        new AlertDialog.Builder(this)
                .setTitle("Novo nome")
                .setView(campo)
                .setPositiveButton("Salvar", (d, w) -> {
                    String novo = campo.getText().toString().trim();
                    if (novo.isEmpty()) return;
                    if (!novo.endsWith(".mp4")) novo = novo + ".mp4";
                    try {
                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.Video.Media.DISPLAY_NAME, novo);
                        getContentResolver().update(v.uri, cv, null, null);
                        carregar();
                    } catch (Exception e) {
                        aviso("Não consegui renomear");
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void compartilhar(Video v) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("video/mp4");
        i.putExtra(Intent.EXTRA_STREAM, v.uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Compartilhar vídeo"));
    }

    private void excluir(Video v) {
        new AlertDialog.Builder(this)
                .setTitle("Excluir vídeo")
                .setMessage("Apagar \"" + v.nome + "\" de vez?")
                .setPositiveButton("Excluir", (d, w) -> {
                    try {
                        getContentResolver().delete(v.uri, null, null);
                        carregar();
                    } catch (Exception e) {
                        aviso("Não consegui excluir");
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void aviso(String texto) {
        Toast.makeText(this, texto, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------
    private class Adaptador extends BaseAdapter {

        @Override
        public int getCount() {
            return lista.size();
        }

        @Override
        public Object getItem(int i) {
            return lista.get(i);
        }

        @Override
        public long getItemId(int i) {
            return lista.get(i).id;
        }

        @Override
        public View getView(int pos, View reaproveitar, ViewGroup pai) {
            View linha = reaproveitar;
            if (linha == null) {
                linha = LayoutInflater.from(VideosActivity.this)
                        .inflate(R.layout.item_video, pai, false);
            }

            Video v = lista.get(pos);
            TextView nome = linha.findViewById(R.id.itemNome);
            TextView detalhe = linha.findViewById(R.id.itemDetalhe);
            ImageView capa = linha.findViewById(R.id.itemCapa);

            nome.setText(v.nome);
            long s = v.duracaoMs / 1000;
            String duracao = String.format(Locale.getDefault(),
                    "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
            String tamanho = String.format(Locale.getDefault(), "%.1f MB",
                    v.bytes / (1024.0 * 1024.0));
            String data = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                    .format(new Date(v.dataSeg * 1000L));
            detalhe.setText(duracao + "  •  " + tamanho + "  •  " + data);

            capa.setImageDrawable(null);
            capa.setTag(v.uri);
            carregarCapa(v.uri, capa);
            return linha;
        }
    }

    /** Miniatura carregada fora da tela principal para nao travar a rolagem. */
    private void carregarCapa(Uri uri, ImageView alvo) {
        fila.execute(() -> {
            try {
                Bitmap bmp = getContentResolver().loadThumbnail(uri, new Size(320, 320), null);
                principal.post(() -> {
                    if (uri.equals(alvo.getTag())) alvo.setImageBitmap(bmp);
                });
            } catch (Exception ignored) {
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        fila.shutdownNow();
    }
}
