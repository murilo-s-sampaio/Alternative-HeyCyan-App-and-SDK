# Plano de migração Android → iOS (CyanBridge)

Objetivo: levar as funções do app Android (`android/CyanBridge/app`) para o app iOS (`CyanBridgeKMPHost`), reaproveitando as telas Compose compartilhadas (`shared/commonMain`) e implementando no iOS só o que é específico da plataforma.

Cada sprint tem 10 tarefas. Uma tarefa só é marcada como concluída quando:

- compila para simulador e iPhone (`CyanBridgeKMPHost` e `CyanBridgeKMPHost-Free`);
- foi testada no iPhone (e com os óculos, quando envolve hardware);
- não quebra o Android (as telas compartilhadas continuam com o comportamento padrão no Android).

## Situação inicial (30/09/2026)

| | Android | iOS |
|---|---|---|
| Código próprio da plataforma | ~95.700 linhas, 480 arquivos | ~2.500 linhas |
| Ações da tela Óculos (`GlassesDashboardAction`, 73 no total) | 66 tratadas | 12 tratadas |
| Controle dos óculos | SDK do fabricante (`glasses_sdk.aar`) | Bluetooth genérico, sem SDK |
| Telas compartilhadas ligadas | todas | Óculos, Pareamento, Conversas, Mídia, Plugins, Configurações, Aparência, Pro |

A causa principal da diferença é o controle dos óculos: o Android usa o SDK do fabricante e o iOS não usa o equivalente (`ios/QCSDK.framework`), que já está no repositório. Por isso a Sprint 1 começa por ele.

## Sprint 0 — Base para rodar no iPhone (concluída)

- [x] Configuração `Debug-Free` e schemes `QCSDKDemo-Free` / `CyanBridgeKMPHost-Free` para conta Apple gratuita (sem entitlements de Hotspot/Wi-Fi).
- [x] Conexão Wi-Fi manual no QCSDKDemo quando a conta não tem a entitlement de Hotspot.
- [x] Crash ao abrir: `NSLog` com argumentos variáveis no `PlatformLogger` do iOS.
- [x] Crash ao abrir: chave `CADisableMinimumFrameDurationOnPhone` exigida pelo Compose; `UILaunchScreen` para tela cheia.
- [x] Build do Kotlin bloqueada pelo "User Script Sandboxing" do Xcode no target do CyanBridge.
- [x] Framework Kotlin do iPhone passou a ser dinâmico (o estático impedia a instalação).
- [x] Examinar abre a tela de pareamento compartilhada (lista, tipo, conectar); Reconectar usa o último perfil salvo.
- [x] Barra de navegação duplicada nas abas Conversas, Mídia, Plugins, Configurações e na conversa aberta.
- [x] Acentos da tradução pt-BR compartilhada (231 textos) e rótulo "Ajustes" na barra.
- [x] App instalado e aberto no iPhone 13 de teste.

## Sprint 1 — SDK do fabricante e controles básicos dos óculos

Status: implementada; compila para iPhone e simulador e abre sem crash. Teste com os óculos pendente.

| # | Tarefa | Android (origem) | iOS (destino) |
|---|---|---|---|
| 1.1 | Linkar `QCSDK.framework` no `CyanBridgeKMPHost` sem afetar o QCSDKDemo | `glasses_sdk.aar` | build settings do target |
| 1.2 | Interface Kotlin `VendorGlassesBridge` (comandos + eventos) no `iosMain`, registrada pelo Swift | `LargeDataHandler`, `BleOperateManager` | `iosMain` |
| 1.3 | Implementação Swift com QCSDK; entregar o `CBPeripheral` conectado ao `QCSDKManager.addPeripheral` e remover ao desconectar | conexão do SDK | `QCSDKManager` |
| 1.4 | Detectar HeyCyan na lista de pareamento pelos UUIDs de serviço do SDK | `DeviceClassifier` | `QCSDKSERVERUUID1/2` |
| 1.5 | Bateria (consulta + atualização automática) | `RequestBattery` | `getDeviceBattery`, `didUpdateBatteryLevel` |
| 1.6 | Versão de firmware e hardware | `RequestVersion` | `getDeviceVersionInfoSuccess` |
| 1.7 | Tirar foto | `CapturePhoto` | `setDeviceMode(Photo)` |
| 1.8 | Gravar vídeo (iniciar/parar) | `ToggleVideo` | `setDeviceMode(Video/VideoStop)` |
| 1.9 | Gravar áudio (iniciar/parar) e contagem de mídia | `StartAudioRecording`, `RequestMediaCount` | `setDeviceMode(Audio/AudioStop)`, `getDeviceMedia` |
| 1.10 | Sincronizar hora ao conectar e reconectar automaticamente ao abrir o app | `SyncTime`, AutoPair | `setupDeviceDateTime` |

## Sprint 2 — Configurações dos óculos e transferência de mídia

| # | Tarefa | Android (origem) | iOS (destino) |
|---|---|---|---|
| 2.1 | Volume (ler e ajustar) | `RequestVolume` | `getVolume`, `setVolume` |
| 2.2 | Detecção de uso (óculos no rosto) | `SetWearingDetection` | `get/setWearingDetection` |
| 2.3 | Duração de vídeo e áudio | `SetVideoRecordingDuration`, `SetAudioRecordingDuration`, `RefreshRecordingSettings` | `setVideoInfo`, `setAudioInfo`, `getVideoInfo`, `getAudioInfo` |
| 2.4 | Palavra de ativação e modo de fala da IA | `SetAiWakeWordRoute` | `setVoiceWakeup`, `setAISpeekModel` |
| 2.5 | Modo transferência pelo SDK, entregando SSID/senha/IP ao fluxo compartilhado | `glassesControl([0x02,0x01,0x04])` | `openWifiWithMode(Transfer)`, `getDeviceWifiIP` → `configurePreparedHotspot` |
| 2.6 | Entrar no hotspot: automático na build paga, manual na Free (sem depender de `NEHotspotNetwork.fetchCurrent`) | Wi-Fi Direct | `NEHotspotConfiguration` / instruções manuais |
| 2.7 | Baixar `media.config` e arquivos com progresso | `startDataDownload()` | `IosMediaTransfer` |
| 2.8 | Salvar JPG/MP4 na Fototeca; OPUS → Ogg | MediaStore, wrapper Ogg | `PHPhotoLibrary`, código Kotlin compartilhado |
| 2.9 | Galeria de mídia sincronizada com miniaturas | `SyncedMediaGalleryActivity` | `SyncedMediaGalleryScreen` |
| 2.10 | Apagar mídia dos óculos após sincronizar (opcional) | — | `deleleteMedia`, `deleleteAllMedias` |

## Sprint 3 — Conversas, notas e perguntas à IA

| # | Tarefa | Android (origem) | iOS (destino) |
|---|---|---|---|
| 3.1 | Notas na aba Conversas (hoje `notes = emptyList()` no iOS) | `NotesRepository` | `IosNotesRepository` |
| 3.2 | Editor de notas | `NoteEditorActivity` | `NotesScreens` (compartilhada) |
| 3.3 | Anexar imagem na conversa | seletor de imagens | `PHPickerViewController` |
| 3.4 | Entrada por voz na conversa | gravação + `/transcribe` | `AVAudioRecorder` + relay |
| 3.5 | Pergunta por imagem a partir dos óculos | `TestImageQuestion` | `setDeviceMode(AIPhoto)`, `didReceiveAIChatImageData` → `/image-query` |
| 3.6 | Resposta falada (TTS) no áudio dos óculos | TTS Android | `AVSpeechSynthesizer` |
| 3.7 | Pergunta por voz pelo microfone dos óculos | `TestVoiceQuestion` | `AVAudioSession` (Bluetooth HFP) → `/voice-query` |
| 3.8 | Modo do assistente (Local / Pro) | `SelectAssistantMode` | relay |
| 3.9 | Aparência da conversa (balões, fundo) persistida | `ChatThreadActivity` | preferências iOS |
| 3.10 | Traduzir textos fixos em inglês ("Notes & Chats", "Chats", "Notes") | — | `composeResources` |

## Sprint 4 — Reuniões, gravações e Configurações

| # | Tarefa | Android (origem) | iOS (destino) |
|---|---|---|---|
| 4.1 | Captura de reunião em segundo plano | `MeetingCaptureService` | `AVAudioRecorder` + modo de fundo `audio` |
| 4.2 | Timer de reunião | `SelectMeetingTimer` | controller iOS |
| 4.3 | Lista de gravações e reprodução | `RecordingsListActivity` | `RecordingsScreen` + `AVAudioPlayer` |
| 4.4 | Transcrição e diálogo de transcrição | `/transcribe` | relay |
| 4.5 | Resumo de reunião | resumidor offline + relay | Kotlin compartilhado |
| 4.6 | Exportar e importar dados locais | `exportLocalData`, `importLocalData` | Share Sheet / `UIDocumentPicker` |
| 4.7 | Limpar dados; importar ChatGPT/Claude | `clearLocalData`, `importChatGptData`, `importClaudeData` | idem |
| 4.8 | Cofre de memória com frase secreta | `setVaultPassphrase`, `clearVaultPassphrase` | Keychain |
| 4.9 | Enviar logs de depuração | `/logs/submit` | relay |
| 4.10 | Idioma do app, boas-vindas e onboarding no primeiro uso | `WelcomeActivity`, `OnboardingFeatureActivity` | `WelcomeScreen`, `FeatureOnboardingScreen` |

## Sprint 5 — Plugins e atualização de firmware

Pagamento, assinatura Pro e cobrança ficam fora deste plano por decisão do produto.

| # | Tarefa | Android (origem) | iOS (destino) |
|---|---|---|---|
| 5.1 | Plugin Walking Aid | `WalkingAidService`, `WalkingAidChatActivity` | iOS |
| 5.2 | Plugin Live Caption Relay | `LiveCaptionRelayService` | iOS |
| 5.3 | Plugin Errand Brain (lembretes) | `ErrandBrainService`, `ErrandBrainReminderReceiver` | `UNUserNotificationCenter` |
| 5.4 | Catálogo de plugins nativos: ligar os viáveis no iOS e marcar os inviáveis | `CommunityPluginsActivity` | `CommunityPluginsScreen` |
| 5.5 | Plugin Meeting Spark Notes | `MeetingSparkNotesService` | iOS |
| 5.6 | Plugin Hands-Free Translator | `HandsFreeTranslatorService` | iOS |
| 5.7 | Verificar atualização de firmware | `/last-ota` | relay |
| 5.8 | Atualização de firmware por Wi-Fi e por Bluetooth, com progresso e cancelar | `RequestOtaFirmware`, `CancelOta` | `sendOTAFileLink`, APIs DFU do QCSDK |
| 5.9 | Pedido de patch de firmware | `SubmitFirmwarePatchRequest` | relay |
| 5.10 | Publicar plugin | `PublishPluginActivity` | `PublishPluginScreen` |

## Sprint 6 — Outros óculos e recursos avançados

| # | Tarefa | Android (origem) | iOS (destino) |
|---|---|---|---|
| 6.1 | Meta Ray-Ban: pareamento e registro | MWDAT Android | SDK Meta Wearables para iOS |
| 6.2 | Meta: sessão, foto e transmissão | `Meta*` actions | idem |
| 6.3 | Meizu MYVU (verificar se o protocolo é só BLE) | `MeizuMyvuConnectionService` | CoreBluetooth |
| 6.4 | EyeVue (verificar protocolo) | `EyevueManager` | CoreBluetooth |
| 6.5 | MoYoung / W620 | `MoyoungW620Manager` | CoreBluetooth |
| 6.6 | Mentra Live | SDK Mentra | SDK Mentra iOS, se existir |
| 6.7 | Prévia ao vivo da câmera | `StartLivePreview` (RTSP) | player RTSP (ex.: VLCKit) |
| 6.8 | Gemini Live | `GeminiLiveActivity` | WebSocket + áudio iOS |
| 6.9 | Modelos de IA locais (estudo de viabilidade) | llama.cpp / LiteRT | llama.cpp para iOS ou MLX |
| 6.10 | Integrações de conhecimento | `KnowledgeIntegrationsActivity` | iOS |

## Fora do escopo (o iOS não permite)

| Função Android | Motivo |
|---|---|
| Pagamento, assinatura Pro e cobrança | Fora do plano por decisão do produto |
| Local Agent, AutoDiary (leitura da tela) | Depende do serviço de acessibilidade do Android |
| Tasker, TaskerNet | Não existe no iOS |
| Wi-Fi ADB (`RequestStartWifiAdbDebug`) | Não existe no iOS |
| TuneBuds (`StartClassicBluetoothScan`) | Bluetooth Classic RFCOMM exige certificação MFi da Apple |
| Wi-Fi Direct | O iOS entra no hotspot dos óculos no lugar |
| Encaminhar notificações de outros apps | O iOS não deixa um app ler notificações de outros |
| Guia de otimização de bateria | Conceito exclusivo do Android |
