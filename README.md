<div align="center">

<img src="docs/icone.svg" width="120" alt="GravaTela">

# GravaTela

**Gravador de tela para Android — vídeo em alta qualidade com o áudio interno do celular.**

![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Java](https://img.shields.io/badge/Java-17-orange?style=flat-square&logo=openjdk&logoColor=white)
![Licença](https://img.shields.io/badge/licen%C3%A7a-MIT-blue?style=flat-square)
![Dependências](https://img.shields.io/badge/depend%C3%AAncias-zero-lightgrey?style=flat-square)

</div>

---

## Sobre

O **GravaTela** grava tudo o que acontece na tela do celular e salva direto na galeria, no mesmo formato `.mp4` que a câmera do aparelho usa.

Diferente da maioria dos gravadores, ele captura **apenas o som interno** — o áudio que o próprio celular está tocando. O microfone nunca é ligado, então nada do ambiente ao redor entra no vídeo.

Tudo foi construído em **Android nativo puro**, sem bibliotecas de terceiros: o app usa apenas as APIs do próprio sistema.

## Recursos

- 🎬 Gravação da tela inteira, com qualquer app rodando por cima
- 🔊 Captura só do áudio interno (microfone nunca é usado)
- ⏸️ Pausar e retomar a gravação sem gerar arquivos separados
- ✂️ Corte automático por tempo — ao atingir o limite, o vídeo é salvo e a gravação **continua** em um arquivo novo, sem interrupção
- ⚙️ Três qualidades: 1080p, 720p e 480p
- 🫧 Bola flutuante arrastável com cronômetro e controles, disponível por cima de qualquer app
- 📁 Tela "Meus vídeos" com miniatura, renomear, compartilhar e excluir
- 💾 Salvamento pela MediaStore — o vídeo aparece na galeria sem pedir permissão de armazenamento

## Como funciona por dentro

O app não usa `MediaRecorder`. Para conseguir capturar o áudio interno junto com a imagem, o pipeline é montado à mão:

```
Tela  ──► VirtualDisplay ──► MediaCodec (H.264) ──┐
                                                  ├──► MediaMuxer ──► arquivo .mp4
Áudio ──► AudioRecord ─────► MediaCodec (AAC) ────┘
          (AudioPlaybackCapture)
```

| Arquivo | Responsabilidade |
| --- | --- |
| `MainActivity.java` | Tela inicial, permissões e consentimento de captura |
| `RecorderService.java` | Serviço em primeiro plano que mantém a gravação viva fora do app |
| `ScreenRecorder.java` | O motor: codificação de vídeo e áudio, sincronização, pausa e corte automático |
| `BubbleService.java` | Bola flutuante sobre outros apps (`TYPE_APPLICATION_OVERLAY`) |
| `VideosActivity.java` | Lista das gravações via consulta à MediaStore |
| `SettingsActivity.java` | Preferências de qualidade, som, tempo e bola flutuante |
| `Prefs.java` | Leitura e escrita das preferências |

**Detalhes de implementação que valem nota:**

- A pausa não corta o arquivo: o tempo parado é descontado dos *presentation timestamps*, e um quadro-chave é solicitado ao retomar, evitando o congelamento de imagem típico dessa operação.
- O corte automático acontece exatamente sobre um quadro-chave, garantindo que o arquivo seguinte comece com uma imagem completa.
- A gravação roda em serviço de primeiro plano do tipo `mediaProjection`, exigência do Android 14+.

## Requisitos

- Android 10 (API 29) ou superior — a captura de áudio interno não existe em versões anteriores
- Android Studio com JDK 17

## Como compilar

```bash
git clone https://github.com/sandrolimadf1984/gravatela.git
cd gravatela
./gradlew assembleDebug
```

O APK sai em `app/build/outputs/apk/debug/app-debug.apk`.

Pelo Android Studio: **File → Open**, aponte para a pasta do projeto, espere o Gradle sincronizar e use o botão ▶.

## Permissões usadas

| Permissão | Para quê |
| --- | --- |
| `RECORD_AUDIO` | Exigida pelo Android para capturar o áudio interno (o microfone não é acessado) |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Manter a gravação ativa fora do app |
| `SYSTEM_ALERT_WINDOW` | Bola flutuante sobre outros apps |
| `POST_NOTIFICATIONS` | Notificação com os controles de pausar e parar |

## Limitações conhecidas

- **Conteúdo protegido por DRM** (Netflix, Prime Video, Disney+, HBO Max) grava tela preta. É uma trava do próprio Android e afeta qualquer gravador de tela.
- A orientação é travada no momento em que a gravação começa; girar o aparelho durante a captura deforma a imagem.
- Overlays desenhados por apps aparecem no vídeo, inclusive a bola flutuante. Existe a opção *Esconder a bola durante a gravação* nas Definições para contornar isso.

## Autoria

Desenvolvido por **Sandro** — [@sandrolimadf1984](https://github.com/sandrolimadf1984)

## Licença

Distribuído sob a licença MIT. Veja [LICENSE](LICENSE) para mais detalhes.
