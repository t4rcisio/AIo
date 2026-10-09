package com.aicall.shell;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Looper;

public class DualStreamTester {
    public static void main(String[] args) {
        if (Looper.myLooper() == null) {
            try { Looper.prepare(); } catch (Throwable ignored) {}
        }

        int sampleRate = 16000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        int bufSize = Math.max(minBuf * 2, 8192);

        AudioRecord uplinkRec = null;
        AudioRecord downlinkRec = null;

        try {
            System.out.println("[DUAL] Criando AudioRecord para VOICE_UPLINK (2)...");
            uplinkRec = new AudioRecord(MediaRecorder.AudioSource.VOICE_UPLINK, sampleRate, channelConfig, audioFormat, bufSize);
            System.out.println("[DUAL] Uplink state: " + uplinkRec.getState());

            System.out.println("[DUAL] Criando AudioRecord para VOICE_DOWNLINK (3)...");
            downlinkRec = new AudioRecord(MediaRecorder.AudioSource.VOICE_DOWNLINK, sampleRate, channelConfig, audioFormat, bufSize);
            System.out.println("[DUAL] Downlink state: " + downlinkRec.getState());

            if (uplinkRec.getState() == AudioRecord.STATE_INITIALIZED && downlinkRec.getState() == AudioRecord.STATE_INITIALIZED) {
                System.out.println("[DUAL] Iniciando startRecording simultâneo...");
                uplinkRec.startRecording();
                System.out.println("[DUAL] Uplink recording: " + uplinkRec.getRecordingState());
                downlinkRec.startRecording();
                System.out.println("[DUAL] Downlink recording: " + downlinkRec.getRecordingState());
                System.out.println("[DUAL] SUCESSO: Ambos os AudioRecords podem rodar em paralelo!");
            } else {
                System.out.println("[DUAL] Pelo menos um AudioRecord não inicializou.");
            }
        } catch (Throwable t) {
            System.out.println("[DUAL] Erro: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (uplinkRec != null) {
                try { uplinkRec.stop(); } catch (Throwable ignored) {}
                try { uplinkRec.release(); } catch (Throwable ignored) {}
            }
            if (downlinkRec != null) {
                try { downlinkRec.stop(); } catch (Throwable ignored) {}
                try { downlinkRec.release(); } catch (Throwable ignored) {}
            }
        }

        // Teste 2: VOICE_CALL em STEREO
        AudioRecord stereoRec = null;
        try {
            System.out.println("[STEREO] Criando AudioRecord para VOICE_CALL em STEREO...");
            int stereoMinBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, audioFormat);
            stereoRec = new AudioRecord(MediaRecorder.AudioSource.VOICE_CALL, sampleRate, AudioFormat.CHANNEL_IN_STEREO, audioFormat, Math.max(stereoMinBuf * 2, 16384));
            System.out.println("[STEREO] Stereo state: " + stereoRec.getState());
            if (stereoRec.getState() == AudioRecord.STATE_INITIALIZED) {
                stereoRec.startRecording();
                System.out.println("[STEREO] Stereo recording: " + stereoRec.getRecordingState());
                System.out.println("[STEREO] SUCESSO: VOICE_CALL Stereo pode gravar 2 canais!");
            }
        } catch (Throwable t) {
            System.out.println("[STEREO] Erro: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (stereoRec != null) {
                try { stereoRec.stop(); } catch (Throwable ignored) {}
                try { stereoRec.release(); } catch (Throwable ignored) {}
            }
        }
    }
}
