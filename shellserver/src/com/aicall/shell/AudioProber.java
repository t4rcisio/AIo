package com.aicall.shell;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Looper;

public class AudioProber {
    public static void main(String[] args) {
        if (Looper.myLooper() == null) {
            try { Looper.prepare(); } catch (Throwable ignored) {}
        }

        int[] sources = new int[]{
            MediaRecorder.AudioSource.VOICE_UPLINK,   // 2
            MediaRecorder.AudioSource.VOICE_DOWNLINK, // 3
            MediaRecorder.AudioSource.VOICE_CALL,     // 4
            MediaRecorder.AudioSource.MIC             // 1
        };

        String[] sourceNames = new String[]{
            "VOICE_UPLINK (2)",
            "VOICE_DOWNLINK (3)",
            "VOICE_CALL (4)",
            "MIC (1)"
        };

        int[] channelConfigs = new int[]{
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.CHANNEL_IN_STEREO
        };

        String[] channelNames = new String[]{"MONO", "STEREO"};

        for (int i = 0; i < sources.length; i++) {
            for (int j = 0; j < channelConfigs.length; j++) {
                int src = sources[i];
                int ch = channelConfigs[j];
                String name = sourceNames[i] + " + " + channelNames[j];
                try {
                    int minBuf = AudioRecord.getMinBufferSize(16000, ch, AudioFormat.ENCODING_PCM_16BIT);
                    if (minBuf <= 0) {
                        System.out.println(name + " -> getMinBufferSize: " + minBuf);
                        continue;
                    }
                    AudioRecord rec = new AudioRecord(src, 16000, ch, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
                    int state = rec.getState();
                    boolean ok = (state == AudioRecord.STATE_INITIALIZED);
                    System.out.println(name + " -> STATE: " + (ok ? "INITIALIZED (SUCCESS)" : "NOT_INITIALIZED (" + state + ")"));
                    try { rec.release(); } catch (Throwable ignored) {}
                } catch (Throwable t) {
                    System.out.println(name + " -> EXCEPTION: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        }
    }
}
