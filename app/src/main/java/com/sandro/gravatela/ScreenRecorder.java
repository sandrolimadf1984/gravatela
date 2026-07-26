package com.sandro.gravatela;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Motor de gravacao.
 *
 * Video: a tela e espelhada direto para dentro do codificador H.264.
 * Audio: pega SO o som que o celular esta tocando (nao usa microfone).
 * Os dois entram num MediaMuxer que gera o arquivo .mp4 na galeria.
 */
public class ScreenRecorder {

    private static final String TAG = "GravaTela";
    private static final int TAXA_AUDIO = 44100;
    private static final int CANAIS = 2;

    private final Context ctx;
    private final MediaProjection projecao;

    // video
    private MediaCodec encVideo;
    private Surface superficie;
    private VirtualDisplay display;
    private int larg, alt, dpi, bitrate, fps;

    // audio
    private MediaCodec encAudio;
    private AudioRecord audioRecord;
    private boolean comAudio = false;
    private int tamBufferAudio;

    // arquivo
    private MediaMuxer muxer;
    private ParcelFileDescriptor pfd;
    private Uri uriAtual;
    private int trilhaVideo = -1, trilhaAudio = -1;
    private MediaFormat formatoVideo, formatoAudio;
    private boolean muxerLigado = false;
    private boolean escreveuAlgo = false;
    private int numeroParte = 1;
    private final Object trava = new Object();

    // tempo
    private long limiteUs;
    private long inicioVideoUs = -1;
    private long pausadoTotalUs = 0;
    private long pausaInicioNs = 0;
    private long baseArquivoVideoUs = 0;
    private long baseArquivoAudioUs = 0;
    private long amostrasAudio = 0;
    private boolean pediuChave = false;

    private volatile boolean rodando = false;
    private volatile boolean pausado = false;
    private Thread threadVideo, threadAudio;

    public ScreenRecorder(Context ctx, MediaProjection projecao) {
        this.ctx = ctx.getApplicationContext();
        this.projecao = projecao;
    }

    // ------------------------------------------------------------------
    // INICIAR
    // ------------------------------------------------------------------
    public void iniciar() throws Exception {
        bitrate = Prefs.bitrate(ctx);
        fps = Prefs.fps(ctx);
        limiteUs = Prefs.limiteMinutos(ctx) * 60L * 1_000_000L;

        calcularTamanho();
        prepararVideo();
        prepararAudio();
        novoArquivo();

        display = projecao.createVirtualDisplay(
                "GravaTela",
                larg, alt, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                superficie, null, null);

        rodando = true;

        threadVideo = new Thread(this::loopVideo, "video");
        threadVideo.start();

        if (comAudio) {
            audioRecord.startRecording();
            threadAudio = new Thread(this::loopAudio, "audio");
            threadAudio.start();
        }
    }

    /** Descobre o tamanho real da tela e reduz para a qualidade escolhida. */
    private void calcularTamanho() {
        DisplayMetrics m = new DisplayMetrics();
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        d.getRealMetrics(m);

        int w = m.widthPixels;
        int h = m.heightPixels;
        dpi = m.densityDpi;

        int alvo = Prefs.ladoMenor(ctx);
        int menor = Math.min(w, h);
        if (menor > alvo) {
            float escala = (float) alvo / (float) menor;
            w = Math.round(w * escala);
            h = Math.round(h * escala);
        }

        larg = w - (w % 16);
        alt = h - (h % 16);
        if (larg < 160 || alt < 160) {   // rede de seguranca
            larg = 720;
            alt = 1280;
        }
        Log.i(TAG, "gravando em " + larg + "x" + alt + " @" + fps + "fps");
    }

    private void prepararVideo() throws Exception {
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, larg, alt);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

        encVideo = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encVideo.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        superficie = encVideo.createInputSurface();
        encVideo.start();
    }

    /** Captura so o som interno (USAGE_MEDIA / GAME / UNKNOWN). Microfone nao entra. */
    private void prepararAudio() {
        if (!Prefs.somInterno(ctx)) return;
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "sem permissao de audio, gravando so o video");
            return;
        }
        try {
            AudioPlaybackCaptureConfiguration config =
                    new AudioPlaybackCaptureConfiguration.Builder(projecao)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();

            AudioFormat formato = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(TAXA_AUDIO)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();

            int minimo = AudioRecord.getMinBufferSize(TAXA_AUDIO,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            tamBufferAudio = Math.max(minimo * 2, 8192);

            audioRecord = new AudioRecord.Builder()
                    .setAudioFormat(formato)
                    .setBufferSizeInBytes(tamBufferAudio)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();

            MediaFormat fa = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, TAXA_AUDIO, CANAIS);
            fa.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            fa.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
            fa.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, tamBufferAudio);

            encAudio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encAudio.configure(fa, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encAudio.start();

            comAudio = true;
        } catch (Exception e) {
            Log.e(TAG, "audio interno nao disponivel: " + e.getMessage());
            comAudio = false;
        }
    }

    // ------------------------------------------------------------------
    // ARQUIVO NA GALERIA
    // ------------------------------------------------------------------
    private void novoArquivo() throws Exception {
        String data = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String nome = "GravaTela_" + data
                + (numeroParte > 1 ? "_parte" + numeroParte : "") + ".mp4";

        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, nome);
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GravaTela");
        v.put(MediaStore.Video.Media.IS_PENDING, 1);

        uriAtual = ctx.getContentResolver()
                .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        pfd = ctx.getContentResolver().openFileDescriptor(uriAtual, "rw");
        muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        muxerLigado = false;
        escreveuAlgo = false;
    }

    private void fecharArquivo() {
        try {
            if (muxerLigado && escreveuAlgo) muxer.stop();
        } catch (Exception e) {
            Log.w(TAG, "muxer.stop: " + e.getMessage());
        }
        try {
            if (muxer != null) muxer.release();
        } catch (Exception ignored) {
        }
        try {
            if (pfd != null) pfd.close();
        } catch (Exception ignored) {
        }
        muxer = null;
        pfd = null;

        if (uriAtual == null) return;
        if (escreveuAlgo) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.IS_PENDING, 0);
            ctx.getContentResolver().update(uriAtual, v, null, null);
        } else {
            ctx.getContentResolver().delete(uriAtual, null, null);
        }
        uriAtual = null;
    }

    /** Fecha o arquivo atual e abre o proximo (o corte de 1 hora). */
    private void cortarArquivo() throws Exception {
        fecharArquivo();
        numeroParte++;
        novoArquivo();
        trilhaVideo = muxer.addTrack(formatoVideo);
        if (formatoAudio != null) trilhaAudio = muxer.addTrack(formatoAudio);
        muxer.start();
        muxerLigado = true;
        pediuChave = false;
    }

    private void ligarMuxerSePronto() {
        if (muxerLigado) return;
        boolean videoOk = trilhaVideo >= 0;
        boolean audioOk = !comAudio || trilhaAudio >= 0;
        if (videoOk && audioOk) {
            muxer.start();
            muxerLigado = true;
        }
    }

    // ------------------------------------------------------------------
    // LOOP DO VIDEO
    // ------------------------------------------------------------------
    private void loopVideo() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (rodando) {
                int idx = encVideo.dequeueOutputBuffer(info, 10000);

                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized (trava) {
                        formatoVideo = encVideo.getOutputFormat();
                        trilhaVideo = muxer.addTrack(formatoVideo);
                        ligarMuxerSePronto();
                    }
                } else if (idx >= 0) {
                    ByteBuffer buf = encVideo.getOutputBuffer(idx);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;

                    if (info.size > 0 && buf != null && !pausado) {
                        synchronized (trava) {
                            if (muxerLigado) {
                                if (inicioVideoUs < 0) inicioVideoUs = info.presentationTimeUs;
                                long pts = info.presentationTimeUs - inicioVideoUs - pausadoTotalUs;
                                long ptsArquivo = pts - baseArquivoVideoUs;
                                boolean chave = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                                if (ptsArquivo >= limiteUs && chave) {
                                    cortarArquivo();
                                    baseArquivoVideoUs = pts;
                                    baseArquivoAudioUs = amostrasAudio * 1_000_000L / TAXA_AUDIO;
                                    ptsArquivo = 0;
                                } else if (ptsArquivo >= limiteUs - 1_000_000L && !pediuChave) {
                                    pedirQuadroChave();
                                    pediuChave = true;
                                }

                                if (ptsArquivo >= 0) {
                                    buf.position(info.offset);
                                    buf.limit(info.offset + info.size);
                                    info.presentationTimeUs = ptsArquivo;
                                    muxer.writeSampleData(trilhaVideo, buf, info);
                                    escreveuAlgo = true;
                                }
                            }
                        }
                    }
                    encVideo.releaseOutputBuffer(idx, false);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "loopVideo: " + e.getMessage(), e);
        }
    }

    private void pedirQuadroChave() {
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            encVideo.setParameters(b);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // LOOP DO AUDIO
    // ------------------------------------------------------------------
    private void loopAudio() {
        byte[] pcm = new byte[tamBufferAudio];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (rodando) {
                if (pausado) {
                    Thread.sleep(30);
                    continue;
                }

                int lidos = audioRecord.read(pcm, 0, pcm.length);
                if (lidos > 0) {
                    int idxIn = encAudio.dequeueInputBuffer(10000);
                    if (idxIn >= 0) {
                        ByteBuffer entrada = encAudio.getInputBuffer(idxIn);
                        if (entrada != null) {
                            entrada.clear();
                            entrada.put(pcm, 0, lidos);
                            long pts = amostrasAudio * 1_000_000L / TAXA_AUDIO;
                            encAudio.queueInputBuffer(idxIn, 0, lidos, pts, 0);
                            amostrasAudio += lidos / (CANAIS * 2);
                        }
                    }
                }

                // tira o que ja foi codificado e joga no arquivo
                while (true) {
                    int idxOut = encAudio.dequeueOutputBuffer(info, 0);
                    if (idxOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        synchronized (trava) {
                            formatoAudio = encAudio.getOutputFormat();
                            trilhaAudio = muxer.addTrack(formatoAudio);
                            ligarMuxerSePronto();
                        }
                    } else if (idxOut >= 0) {
                        ByteBuffer buf = encAudio.getOutputBuffer(idxOut);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                        if (info.size > 0 && buf != null) {
                            synchronized (trava) {
                                if (muxerLigado && escreveuAlgo) {
                                    long pts = info.presentationTimeUs - baseArquivoAudioUs;
                                    if (pts >= 0) {
                                        buf.position(info.offset);
                                        buf.limit(info.offset + info.size);
                                        info.presentationTimeUs = pts;
                                        muxer.writeSampleData(trilhaAudio, buf, info);
                                    }
                                }
                            }
                        }
                        encAudio.releaseOutputBuffer(idxOut, false);
                    } else {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "loopAudio: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // PAUSAR / CONTINUAR / PARAR
    // ------------------------------------------------------------------
    public void pausar() {
        if (pausado || !rodando) return;
        pausado = true;
        pausaInicioNs = System.nanoTime();
        try {
            if (comAudio) audioRecord.stop();
        } catch (Exception ignored) {
        }
    }

    public void continuar() {
        if (!pausado || !rodando) return;
        pausadoTotalUs += (System.nanoTime() - pausaInicioNs) / 1000L;
        pausado = false;
        try {
            if (comAudio) audioRecord.startRecording();
        } catch (Exception ignored) {
        }
        pedirQuadroChave();
    }

    public void parar() {
        rodando = false;
        pausado = false;

        try {
            if (threadVideo != null) threadVideo.join(1500);
        } catch (Exception ignored) {
        }
        try {
            if (threadAudio != null) threadAudio.join(1500);
        } catch (Exception ignored) {
        }

        try {
            if (display != null) display.release();
        } catch (Exception ignored) {
        }
        try {
            if (encVideo != null) {
                encVideo.stop();
                encVideo.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (superficie != null) superficie.release();
        } catch (Exception ignored) {
        }
        try {
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (encAudio != null) {
                encAudio.stop();
                encAudio.release();
            }
        } catch (Exception ignored) {
        }

        synchronized (trava) {
            fecharArquivo();
        }
    }
}
