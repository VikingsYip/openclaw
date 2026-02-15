package ai.openclaw.android.voice;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.LiveData;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

public class IFlytekSDKService {
    private static final String TAG = "IFlytekSDKService";

    private Context context;
    private Handler mainHandler;

    // SparkChain instance
    private Object sparkChain;

    // TTS state
    private Object onlineTTS;
    private boolean ttsInitialized = false;
    private String initErrorMessage = null;

    // ASR state
    private Object asr;
    private boolean asrInitialized = false;
    private Thread recordingThread;
    private AtomicBoolean isRecording = new AtomicBoolean(false);

    // LiveData for Kotlin/Compose interop
    private final MutableLiveData<Boolean> isSpeakingLive = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> isListeningLive = new MutableLiveData<>(false);
    private final MutableLiveData<String> asrResultLive = new MutableLiveData<>("");
    private final MutableLiveData<String> statusTextLive = new MutableLiveData<>("Ready");
    private final MutableLiveData<String> debugLogLive = new MutableLiveData<>("");

    public LiveData<Boolean> getIsSpeaking() { return isSpeakingLive; }
    public LiveData<Boolean> getIsListening() { return isListeningLive; }
    public LiveData<String> getAsrResult() { return asrResultLive; }
    public LiveData<String> getStatusText() { return statusTextLive; }
    public LiveData<String> getDebugLog() { return debugLogLive; }

    // Audio settings
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int RECORD_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int RECORD_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    // Audio playback for TTS
    private AudioTrack audioTrack;
    private AtomicBoolean isTTSPlaying = new AtomicBoolean(false);

    // Audio recording for ASR
    private AudioRecord audioRecord;
    private static final int RECORD_SAMPLE_RATE = 16000;

    // Credentials
    private String appId = null;
    private String apiKey = null;
    private String apiSecret = null;

    public IFlytekSDKService(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        // Initialize debug log - use postValue to be thread-safe
        debugLogLive.postValue("");
        appendDebugLog("=== iFlytek Service Created ===");
        loadCredentials();
        appendDebugLog("Credentials loaded");
        initSDK();
    }

    private void loadCredentials() {
        try {
            Properties props = new Properties();
            InputStream is = context.getAssets().open("iflytek_credentials.properties");
            props.load(is);
            is.close();

            appId = props.getProperty("IFLYTEK_APP_ID", "");
            apiKey = props.getProperty("IFLYTEK_API_KEY", "");
            apiSecret = props.getProperty("IFLYTEK_API_SECRET", "");

            Log.d(TAG, "Credentials loaded: appId=" + (appId != null && appId.length() > 5 ? appId.substring(0, 5) : "null"));
        } catch (Exception e) {
            Log.e(TAG, "Failed to load credentials: " + e.getMessage());
        }
    }

    private void initSDK() {
        appendDebugLog("Starting initSDK...");
        new Thread(() -> {
            try {
                Log.d(TAG, "Initializing iFlytek SDK...");
                appendDebugLog("Loading SparkChain class...");

                // Load SparkChain class
                Class<?> sparkChainClass = Class.forName("com.iflytek.sparkchain.core.SparkChain");
                appendDebugLog("SparkChain class found");

                // Get SparkChain instance - use getInst()
                Method getInstMethod = sparkChainClass.getMethod("getInst");
                Object sparkChainInstance = getInstMethod.invoke(null);
                appendDebugLog("Got SparkChain instance");

                // Load SparkChainConfig class
                Class<?> sparkChainConfigClass = Class.forName("com.iflytek.sparkchain.core.SparkChainConfig");
                appendDebugLog("SparkChainConfig class found");

                // Create config using builder pattern - no build() method needed
                Object config = sparkChainConfigClass.getDeclaredMethod("builder").invoke(null);
                appendDebugLog("Builder called");
                config = sparkChainConfigClass.getMethod("appID", String.class).invoke(config, appId);
                config = sparkChainConfigClass.getMethod("apiKey", String.class).invoke(config, apiKey);
                config = sparkChainConfigClass.getMethod("apiSecret", String.class).invoke(config, apiSecret);
                appendDebugLog("Config built, class: " + config.getClass().getName());

                // Initialize SparkChain - try init(Context, SparkChainConfig) first
                Method initMethod;
                int ret = -1;
                try {
                    appendDebugLog("Trying init(Context, SparkChainConfig)...");
                    initMethod = sparkChainClass.getMethod("init", Context.class, sparkChainConfigClass);
                    appendDebugLog("Got init method, invoking...");
                    ret = (int) initMethod.invoke(sparkChainInstance, context, config);
                    appendDebugLog("Init with config result: " + ret);
                } catch (NoSuchMethodException e) {
                    appendDebugLog("No 2-arg init, trying init(Context)...");
                    try {
                        initMethod = sparkChainClass.getMethod("init", Context.class);
                        ret = (int) initMethod.invoke(sparkChainInstance, context);
                        appendDebugLog("Init without config result: " + ret);
                    } catch (Exception e2) {
                        appendDebugLog("ERROR init(Context): " + e2.getMessage());
                        e2.printStackTrace();
                    }
                } catch (Exception e) {
                    appendDebugLog("ERROR init: " + e.getClass().getName() + " - " + e.getMessage());
                    e.printStackTrace();
                }
                appendDebugLog("Init result: " + ret);

                if (ret != 0) {
                    throw new Exception("SparkChain init failed: " + ret);
                }

                Log.d(TAG, "SparkChain initialized successfully");

                // Initialize ASR
                initASR();

                ttsInitialized = true;
                asrInitialized = true;
                updateStatusText("Ready");

            } catch (Exception e) {
                Log.e(TAG, "SDK init error: " + e.getMessage());
                appendDebugLog("ERROR: " + e.getMessage());
                e.printStackTrace();
                initErrorMessage = e.getMessage();
                updateStatusText("Init error: " + e.getMessage());
            }
        }).start();
    }

    private void initASR() {
        try {
            Log.d(TAG, "Initializing iFlytek ASR...");
            appendDebugLog("Creating ASR instance...");

            // Create ASR instance with language, domain, accent
            Class<?> asrClass = Class.forName("com.iflytek.sparkchain.core.asr.ASR");
            asr = asrClass.getDeclaredConstructor(String.class, String.class, String.class).newInstance("zh_cn", "slm", "mandarin");
            appendDebugLog("ASR instance created");

            // Create callback proxy
            Class<?> asrCallbackClass = Class.forName("com.iflytek.sparkchain.core.asr.AsrCallbacks");
            Object callbackProxy = java.lang.reflect.Proxy.newProxyInstance(
                asrCallbackClass.getClassLoader(),
                new Class<?>[]{asrCallbackClass},
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    Log.d(TAG, "ASR callback: " + methodName);
                    if ("onResult".equals(methodName)) {
                        handleAsrResult(args[0]);
                    } else if ("onError".equals(methodName)) {
                        handleAsrError(args[0]);
                    }
                    return null;
                }
            );

            // Register callbacks
            Method registerMethod = asrClass.getMethod("registerCallbacks", asrCallbackClass);
            registerMethod.invoke(asr, callbackProxy);

            // Configure ASR
            asrClass.getMethod("language", String.class).invoke(asr, "zh_cn");
            asrClass.getMethod("accent", String.class).invoke(asr, "mandarin");

            Log.d(TAG, "ASR initialized successfully");

        } catch (Exception e) {
            Log.e(TAG, "ASR init error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ==================== ASR Methods ====================

    public boolean startASR() {
        Log.d(TAG, "startASR called, asrInitialized=" + asrInitialized + ", asr=" + asr);
        appendDebugLog("startASR: init=" + asrInitialized);

        if (!asrInitialized || asr == null) {
            Log.e(TAG, "ASR not initialized, calling initASR...");
            appendDebugLog("Calling initASR...");
            initASR();
            // Wait a bit for initialization
            try { Thread.sleep(1000); } catch (Exception e) {}
            appendDebugLog("initASR done, asr=" + asr + ", init=" + asrInitialized);
        }

        if (!asrInitialized || asr == null) {
            Log.e(TAG, "ASR still not initialized");
            appendDebugLog("ERROR: ASR not initialized");
            updateStatusText("ASR not ready");
            return false;
        }

        if (isListeningLive.getValue()) {
            Log.w(TAG, "ASR already listening");
            appendDebugLog("Already listening");
            return false;
        }

        try {
            Log.d(TAG, "Starting ASR...");
            appendDebugLog("Starting ASR...");

            // Initialize audio record
            int minBufferSize = AudioRecord.getMinBufferSize(RECORD_SAMPLE_RATE, RECORD_CHANNEL_CONFIG, RECORD_AUDIO_FORMAT);
            Log.d(TAG, "minBufferSize: " + minBufferSize);
            appendDebugLog("minBufferSize=" + minBufferSize);

            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid minBufferSize");
                updateStatusText("AudioRecord error");
                return false;
            }

            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                RECORD_SAMPLE_RATE,
                RECORD_CHANNEL_CONFIG,
                RECORD_AUDIO_FORMAT,
                minBufferSize * 2
            );

            Log.d(TAG, "AudioRecord state: " + audioRecord.getState());

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized, state=" + audioRecord.getState());
                updateStatusText("AudioRecord error");
                return false;
            }

            // Start recording
            audioRecord.startRecording();
            isRecording.set(true);

            // Update state
            mainHandler.post(() -> {
                isListeningLive.setValue(true);
                asrResultLive.setValue("");
            });
            updateStatusText("Listening...");

            // Start recording thread
            recordingThread = new Thread(() -> {
                byte[] buffer = new byte[minBufferSize];
                try {
                    while (isRecording.get()) {
                        int read = audioRecord.read(buffer, 0, buffer.length);
                        if (read > 0) {
                            // Write audio data to ASR
                            byte[] audioData = new byte[read];
                            System.arraycopy(buffer, 0, audioData, 0, read);
                            writeAudioData(audioData);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Recording error: " + e.getMessage());
                }
            });
            recordingThread.start();

            // Start ASR - use start(Object) per docs
            Class<?> asrClass = asr.getClass();
            appendDebugLog("Starting ASR, calling start(Object)...");
            try {
                // Use start(Object) - per docs: int start(Object usrTag)
                Method startMethod = asrClass.getMethod("start", Object.class);
                int result = (int) startMethod.invoke(asr, (Object) null);
                appendDebugLog("ASR start result: " + result);
            } catch (Exception e) {
                appendDebugLog("ERROR start: " + e.getClass().getName() + " - " + e.getMessage());
                Log.e(TAG, "ASR start error: " + e.getMessage());
                throw e;
            }

            Log.d(TAG, "ASR started successfully");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "ASR start error: " + e.getMessage());
            e.printStackTrace();
            updateStatusText("ASR error: " + e.getMessage());
            return false;
        }
    }

    private void writeAudioData(byte[] data) {
        if (asr == null || !isRecording.get()) return;

        try {
            Class<?> asrClass = asr.getClass();
            Method writeMethod = asrClass.getMethod("write", byte[].class);
            writeMethod.invoke(asr, data);
        } catch (Exception e) {
            Log.e(TAG, "Write audio error: " + e.getMessage());
        }
    }

    public void stopASR() {
        try {
            Log.d(TAG, "Stopping ASR...");
            isRecording.set(false);

            // Stop recording
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            }

            // Stop ASR
            if (asr != null) {
                Class<?> asrClass = asr.getClass();
                asrClass.getMethod("stop", boolean.class).invoke(asr, false);
            }

            // Update state
            mainHandler.post(() -> {
                isListeningLive.setValue(false);
            });
            updateStatusText("Ready");

            Log.d(TAG, "ASR stopped");

        } catch (Exception e) {
            Log.e(TAG, "ASR stop error: " + e.getMessage());
        }
    }

    private void handleAsrResult(Object result) {
        if (result == null) return;

        try {
            // Get text using getBestMatchText() method per docs
            Method getTextMethod = result.getClass().getMethod("getBestMatchText");
            String text = (String) getTextMethod.invoke(result);
            // Check status first - only process final results (status=2)
            Method getStatusMethod = result.getClass().getMethod("getStatus");
            int status = (int) getStatusMethod.invoke(result);
            appendDebugLog("ASR result: '" + text + "', status: " + status);

            // Only send to gateway when we have text and it's a final result (status=2)
            if (status == 2 && text != null && !text.isEmpty()) {
                final String finalText = text;
                appendDebugLog("Setting result to LiveData...");
                mainHandler.post(() -> {
                    asrResultLive.setValue(finalText);
                    appendDebugLog("Result sent to gateway: " + finalText);
                    Log.d(TAG, "Result sent to LiveData: " + finalText);
                });
                // Don't set isListening = false here - let silence monitor handle it
            } else if (status == 0) {
                // Interim result - just show listening
                appendDebugLog("Interim result, still listening...");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error handling ASR result: " + e.getMessage());
        }
    }

    private void handleAsrError(Object error) {
        if (error == null) return;

        try {
            final String errMsg;
            String extracted = extractString(error, "errMsg");
            errMsg = (extracted != null) ? extracted : "Unknown error";
            Log.e(TAG, "ASR error: " + errMsg);

            final String finalErrMsg = errMsg;
            mainHandler.post(() -> {
                isListeningLive.setValue(false);
                updateStatusText("ASR error: " + finalErrMsg);
            });
        } catch (Exception e) {
            Log.e(TAG, "Error handling ASR error: " + e.getMessage());
        }
    }

    // ==================== TTS Methods ====================

    public boolean startTTS(String text) {
        if (!ttsInitialized) {
            String err = initErrorMessage != null ? initErrorMessage : "TTS not initialized";
            Log.e(TAG, "TTS start failed: " + err);
            updateStatusText("Error: " + err);
            return false;
        }

        if (isSpeakingLive.getValue()) {
            Log.w(TAG, "TTS already speaking");
            return false;
        }

        appendDebugLog(">>> TTS starting: " + text.substring(0, Math.min(50, text.length())) + "...");
        Log.d(TAG, "TTS starting: " + text);

        new Thread(() -> {
            try {
                setSpeaking(true);
                updateStatusText("Synthesizing...");
                appendDebugLog("TTS synthesizing...");

                // Create TTS
                Class<?> onlineTtsClass = Class.forName("com.iflytek.sparkchain.core.tts.OnlineTTS");
                onlineTTS = onlineTtsClass.getDeclaredConstructor(String.class).newInstance("x4_xiaoyan");

                // Create callback proxy
                Class<?> ttsCallbackClass = Class.forName("com.iflytek.sparkchain.core.tts.TTSCallbacks");
                Object callbackProxy = java.lang.reflect.Proxy.newProxyInstance(
                    ttsCallbackClass.getClassLoader(),
                    new Class<?>[]{ttsCallbackClass},
                    (proxy, method, args) -> {
                        String methodName = method.getName();
                        if ("onResult".equals(methodName)) {
                            handleTtsResult(args[0]);
                        } else if ("onError".equals(methodName)) {
                            handleTtsError(args[0]);
                        }
                        return null;
                    }
                );

                Method registerMethod = onlineTtsClass.getMethod("registerCallbacks", ttsCallbackClass);
                registerMethod.invoke(onlineTTS, callbackProxy);

                // Configure TTS
                onlineTtsClass.getMethod("speed", int.class).invoke(onlineTTS, 50);
                onlineTtsClass.getMethod("pitch", int.class).invoke(onlineTTS, 50);
                onlineTtsClass.getMethod("volume", int.class).invoke(onlineTTS, 50);

                // Initialize audio track
                initAudioTrack();

                // Start synthesis
                Method aRunMethod = onlineTtsClass.getMethod("aRun", String.class);
                int ret = (int) aRunMethod.invoke(onlineTTS, text);
                Log.d(TAG, "TTS start returned: " + ret);

                if (ret == 0) {
                    setSpeaking(true);
                    updateStatusText("Playing...");
                } else {
                    setSpeaking(false);
                    updateStatusText("Synthesis failed: " + ret);
                }

            } catch (Exception e) {
                Log.e(TAG, "TTS start error: " + e.getMessage());
                e.printStackTrace();
                setSpeaking(false);
                updateStatusText("Error: " + e.getMessage());
            }
        }).start();

        return true;
    }

    private void handleTtsResult(Object result) {
        if (result == null) return;

        try {
            int status = extractInt(result, "status");
            byte[] audio = extractByteArray(result, "data");
            int len = extractInt(result, "len");

            Log.d(TAG, "TTS status: " + status + ", len: " + len);
            appendDebugLog("TTS status: " + status + ", len: " + len);

            if (audio != null && len > 0) {
                playAudioData(audio, len);
            }

            if (status == 2) {
                appendDebugLog("TTS completed");
                setSpeaking(false);
                updateStatusText("Ready");
                isTTSPlaying.set(false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling TTS result: " + e.getMessage());
        }
    }

    private void handleTtsError(Object error) {
        if (error == null) return;

        try {
            String errMsg = extractString(error, "errMsg");
            if (errMsg == null) errMsg = "Unknown error";
            Log.e(TAG, "TTS error: " + errMsg);
            setSpeaking(false);
            updateStatusText("Error: " + errMsg);
        } catch (Exception e) {
            Log.e(TAG, "Error handling TTS error: " + e.getMessage());
        }
    }

    private int extractInt(Object obj, String fieldName) {
        try {
            try {
                String getterName = "get" + capitalize(fieldName);
                Method getter = obj.getClass().getMethod(getterName);
                Object result = getter.invoke(obj);
                if (result instanceof Integer) {
                    return (Integer) result;
                }
            } catch (NoSuchMethodException e) {
                java.lang.reflect.Field field = obj.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                Object result = field.get(obj);
                if (result instanceof Integer) {
                    return (Integer) result;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "extractInt error: " + e.getMessage());
        }
        return 0;
    }

    private String extractString(Object obj, String fieldName) {
        try {
            try {
                String getterName = "get" + capitalize(fieldName);
                Method getter = obj.getClass().getMethod(getterName);
                Object result = getter.invoke(obj);
                if (result instanceof String) {
                    return (String) result;
                }
            } catch (NoSuchMethodException e) {
                java.lang.reflect.Field field = obj.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                Object result = field.get(obj);
                if (result instanceof String) {
                    return (String) result;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "extractString error: " + e.getMessage());
        }
        return null;
    }

    private byte[] extractByteArray(Object obj, String fieldName) {
        try {
            try {
                String getterName = "get" + capitalize(fieldName);
                Method getter = obj.getClass().getMethod(getterName);
                Object result = getter.invoke(obj);
                if (result instanceof byte[]) {
                    return (byte[]) result;
                }
            } catch (NoSuchMethodException e) {
                java.lang.reflect.Field field = obj.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                Object result = field.get(obj);
                if (result instanceof byte[]) {
                    return (byte[]) result;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "extractByteArray error: " + e.getMessage());
        }
        return null;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private void initAudioTrack() {
        try {
            int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);

            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(
                            new AudioAttributes.Builder()
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                    .build()
                    )
                    .setAudioFormat(
                            new AudioFormat.Builder()
                                    .setSampleRate(SAMPLE_RATE)
                                    .setChannelMask(CHANNEL_CONFIG)
                                    .setEncoding(AUDIO_FORMAT)
                                    .build()
                    )
                    .setBufferSizeInBytes(minBufferSize * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            audioTrack.play();
            isTTSPlaying.set(true);
            Log.d(TAG, "AudioTrack initialized");
        } catch (Exception e) {
            Log.e(TAG, "Failed to init AudioTrack: " + e.getMessage());
        }
    }

    private void playAudioData(byte[] data, int len) {
        if (!isTTSPlaying.get()) return;

        try {
            audioTrack.write(data, 0, len);
            appendDebugLog("TTS playing... (" + len + " bytes)");
        } catch (Exception e) {
            Log.e(TAG, "Audio play error: " + e.getMessage());
        }
    }

    public void stopTTS() {
        try {
            Log.d(TAG, "Stopping TTS...");
            appendDebugLog("TTS stopped");
            isTTSPlaying.set(false);

            if (audioTrack != null) {
                audioTrack.stop();
                audioTrack.release();
                audioTrack = null;
            }

            if (onlineTTS != null) {
                Class<?> onlineTtsClass = onlineTTS.getClass();
                Method stopMethod = onlineTtsClass.getMethod("stop");
                stopMethod.invoke(onlineTTS);
            }

            setSpeaking(false);
            updateStatusText("Ready");
        } catch (Exception e) {
            Log.e(TAG, "TTS stop error: " + e.getMessage());
        }
    }

    private void setSpeaking(boolean speaking) {
        mainHandler.post(() -> isSpeakingLive.setValue(speaking));
    }

    private void updateStatusText(String text) {
        mainHandler.post(() -> statusTextLive.setValue(text));
    }

    private void appendDebugLog(String text) {
        final String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        final String logLine = timestamp + " " + text + "\n";
        mainHandler.post(() -> {
            String current = debugLogLive.getValue();
            if (current == null) current = "";
            // Keep only last 500 chars
            if (current.length() > 500) {
                current = current.substring(current.length() - 500);
            }
            debugLogLive.postValue(current + logLine);
        });
    }
}
