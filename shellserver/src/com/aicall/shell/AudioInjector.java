package com.aicall.shell;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Looper;
import android.os.Process;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.Socket;

/**
 * Injetor de áudio direto no Uplink celular (modem TX) no Android 14/15 (SDK 34+)
 * usando a API privilegiada AudioManager.getCallUplinkInjectionAudioTrack()
 * autorizada sob a permissão CALL_AUDIO_INTERCEPTION (UID 2000 shell).
 */
public class AudioInjector {

    private static AudioTrack currentTrack = null;
    private static int currentTrackSampleRate = -1;
    private static AudioManager audioManager = null;
    private static Method getUplinkMethod = null;

    private static synchronized AudioManager getAudioManager() {
        if (audioManager != null) return audioManager;
        try {
            Context shellContext = new ContextWrapper(null) {
                @Override public String getOpPackageName() { return "com.android.shell"; }
                @Override public String getPackageName() { return "com.android.shell"; }
                @Override public int getDeviceId() { return 0; }
                @Override public Looper getMainLooper() {
                    return Looper.getMainLooper() != null ? Looper.getMainLooper() : Looper.myLooper();
                }
                @Override public AttributionSource getAttributionSource() {
                    return new AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build();
                }
                @Override public ApplicationInfo getApplicationInfo() {
                    ApplicationInfo ai = new ApplicationInfo();
                    ai.packageName = "com.android.shell";
                    ai.uid = Process.myUid();
                    ai.targetSdkVersion = 34;
                    return ai;
                }
                @Override public Context getApplicationContext() { return this; }
            };

            for (Constructor<?> c : AudioManager.class.getDeclaredConstructors()) {
                if (c.getParameterCount() == 1 && c.getParameterTypes()[0] == Context.class) {
                    c.setAccessible(true);
                    audioManager = (AudioManager) c.newInstance(shellContext);
                    break;
                }
            }

            if (audioManager != null) {
                getUplinkMethod = AudioManager.class.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat.class);
                System.out.println("[AudioInjector] AudioManager inicializado com sucesso para injeção.");
            }
        } catch (Throwable t) {
            System.err.println("[AudioInjector] Falha ao inicializar AudioManager: " + t.getMessage());
            t.printStackTrace();
        }
        return audioManager;
    }

    public static synchronized AudioTrack getOrCreateUplinkTrack(int sampleRate) {
        if (currentTrack != null && currentTrackSampleRate == sampleRate 
                && currentTrack.getState() == AudioTrack.STATE_INITIALIZED) {
            if (currentTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                try { currentTrack.play(); } catch (Throwable ignored) {}
            }
            return currentTrack;
        }

        if (currentTrack != null) {
            try {
                currentTrack.stop();
                currentTrack.release();
            } catch (Throwable ignored) {}
            currentTrack = null;
        }

        try {
            AudioManager am = getAudioManager();
            if (am != null && getUplinkMethod != null) {
                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build();

                currentTrack = (AudioTrack) getUplinkMethod.invoke(am, format);
                if (currentTrack != null) {
                    currentTrackSampleRate = sampleRate;
                    currentTrack.play();
                    System.out.println("[AudioInjector] Novo Uplink AudioTrack criado com sucesso (" + sampleRate + " Hz)");
                }
            }
        } catch (Throwable t) {
            System.err.println("[AudioInjector] Erro ao criar Uplink AudioTrack: " + t.getMessage());
        }
        return currentTrack;
    }

    public static void handleInjectionStream(Socket socket, InputStream is, int sampleRate) {
        System.out.println("[AudioInjector] Iniciando canal de injeção direta de áudio (" + sampleRate + " Hz)");
        AudioMuter.muteLocalAudio();
        AudioTrack track = getOrCreateUplinkTrack(sampleRate);
        if (track == null) {
            // fallback para 16kHz
            track = getOrCreateUplinkTrack(16000);
        }

        if (track == null) {
            System.err.println("[AudioInjector] Impossível injetar: AudioTrack de uplink não pôde ser instanciado.");
            return;
        }

        byte[] buffer = new byte[3200];
        long totalBytesWritten = 0;
        try {
            int read;
            while (!socket.isClosed() && (read = is.read(buffer)) != -1) {
                if (read > 0) {
                    if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                        try { track.play(); } catch (Throwable ignored) {}
                    }
                    int written = track.write(buffer, 0, read, AudioTrack.WRITE_BLOCKING);
                    if (written > 0) {
                        totalBytesWritten += written;
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[AudioInjector] Conexão de injeção encerrada: " + t.getMessage());
        } finally {
            System.out.println("[AudioInjector] Canal de injeção concluído. Total de bytes injetados no uplink: " + totalBytesWritten);
        }
    }
}
