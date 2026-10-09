package com.aicall.shell;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * Utilitário executado no processo shell via app_process para testar a captura
 * experimental de áudio com AudioRecord(AudioSource.VOICE_CALL) e salvar a prova em WAV.
 */
public class AudioTester {

    public static final String PRIMARY_WAV_PATH = "/sdcard/Download/aicall_prova_chamada.wav";
    public static final String BACKUP_WAV_PATH = "/sdcard/Recordings/aicall_prova_chamada.wav";
    public static final String APP_WAV_PATH = "/sdcard/Android/data/com.example.ai_assistant/files/aicall_prova_chamada.wav";

    public static String runTest(int durationSeconds) {
        int[] candidateSampleRates = new int[]{16000, 44100, 48000, 8000};
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int channelCount = 1;

        AudioRecord record = null;
        int selectedSampleRate = 16000;
        String initError = null;

        try {
            if (Looper.myLooper() == null) {
                try {
                    Looper.prepare();
                } catch (Throwable ignored) {}
            }

            for (int sr : candidateSampleRates) {
                int minBuf = AudioRecord.getMinBufferSize(sr, channelConfig, audioFormat);
                if (minBuf > 0) {
                    selectedSampleRate = sr;
                    int bufSize = Math.max(minBuf * 2, 8192);
                    try {
                        record = new AudioRecord(
                            MediaRecorder.AudioSource.VOICE_CALL,
                            sr,
                            channelConfig,
                            audioFormat,
                            bufSize
                        );
                        if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                            break;
                        } else {
                            try { record.release(); } catch (Throwable ignored) {}
                            record = null;
                        }
                    } catch (Throwable t) {
                        initError = t.getClass().getSimpleName() + ": " + t.getMessage();
                        record = null;
                    }
                }
            }
        } catch (Throwable t) {
            initError = t.getClass().getSimpleName() + ": " + t.getMessage();
        }

        if (record == null) {
            String err = initError != null ? initError : "Não foi possível inicializar AudioRecord(VOICE_CALL)";
            return formatResult("ERROR", "STATE_UNINITIALIZED", selectedSampleRate, channelCount, 0, 0, 0.0, 0, 0, null, err);
        }

        int state = record.getState();
        String stateStr = (state == AudioRecord.STATE_INITIALIZED) ? "STATE_INITIALIZED" : "STATE_UNINITIALIZED";

        if (state != AudioRecord.STATE_INITIALIZED) {
            try { record.release(); } catch (Throwable ignored) {}
            return formatResult("ERROR", stateStr, selectedSampleRate, channelCount, 0, 0, 0.0, 0, 0, null, "AudioRecord em STATE_UNINITIALIZED");
        }

        long totalBytes = 0;
        long totalFrames = 0;
        double sumSquares = 0.0;
        short peak = 0;
        long durationMs = 0;
        String execError = null;
        String savedFilePath = null;

        ByteArrayOutputStream pcmStream = new ByteArrayOutputStream();

        try {
            record.startRecording();
            int recordingState = record.getRecordingState();
            if (recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                execError = "AudioRecord não iniciou gravação (recordingState=" + recordingState + ")";
            } else {
                long startTime = System.currentTimeMillis();
                long targetTime = startTime + (durationSeconds * 1000L);
                short[] audioBuffer = new short[2048];
                byte[] byteBuffer = new byte[audioBuffer.length * 2];

                while (System.currentTimeMillis() < targetTime) {
                    int readSamples = record.read(audioBuffer, 0, audioBuffer.length);
                    if (readSamples > 0) {
                        totalBytes += (long) readSamples * 2;
                        totalFrames += readSamples;
                        for (int i = 0; i < readSamples; i++) {
                            short sample = audioBuffer[i];
                            short abs = (short) Math.abs(sample);
                            if (abs > peak) {
                                peak = abs;
                            }
                            sumSquares += (double) sample * sample;

                            // Converte short para little-endian bytes para arquivo WAV
                            byteBuffer[i * 2] = (byte) (sample & 0xff);
                            byteBuffer[i * 2 + 1] = (byte) ((sample >> 8) & 0xff);
                        }
                        pcmStream.write(byteBuffer, 0, readSamples * 2);
                    } else if (readSamples < 0) {
                        execError = "Erro na leitura AudioRecord.read: código " + readSamples;
                        break;
                    } else {
                        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                    }
                }
                durationMs = System.currentTimeMillis() - startTime;
            }
        } catch (Throwable t) {
            execError = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            try { record.stop(); } catch (Throwable ignored) {}
            try { record.release(); } catch (Throwable ignored) {}
        }

        // Salva arquivo WAV nos diretórios acessíveis
        if (pcmStream.size() > 0) {
            byte[] pcmData = pcmStream.toByteArray();
            savedFilePath = saveWavFile(PRIMARY_WAV_PATH, pcmData, selectedSampleRate, channelCount);
            saveWavFile(BACKUP_WAV_PATH, pcmData, selectedSampleRate, channelCount);
            saveWavFile(APP_WAV_PATH, pcmData, selectedSampleRate, channelCount);
        }

        double rms = totalFrames > 0 ? Math.sqrt(sumSquares / totalFrames) : 0.0;
        String status = (execError == null) ? "OK" : "ERROR";

        return formatResult(status, stateStr, selectedSampleRate, channelCount, totalBytes, totalFrames, rms, peak, durationMs, savedFilePath, execError != null ? execError : "NONE");
    }

    private static String saveWavFile(String path, byte[] pcmData, int sampleRate, int channels) {
        try {
            File file = new File(path);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (file.exists()) {
                file.delete();
            }

            int byteRate = sampleRate * channels * 2;
            byte[] header = createWavHeader(pcmData.length, sampleRate, channels, byteRate);

            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(header);
                fos.write(pcmData);
                fos.flush();
            }
            return path;
        } catch (Throwable t) {
            System.err.println("[AudioTester] Erro ao salvar WAV em " + path + ": " + t.getMessage());
            return null;
        }
    }

    private static byte[] createWavHeader(int totalAudioLen, int sampleRate, int channels, int byteRate) {
        long totalDataLen = (long) totalAudioLen + 36;
        byte[] header = new byte[44];

        header[0] = 'R';
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f';
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16;
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        header[20] = 1; // PCM
        header[21] = 0;
        header[22] = (byte) channels;
        header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (channels * 2);
        header[33] = 0;
        header[34] = 16;
        header[35] = 0;
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);
        return header;
    }

    private static String formatResult(
        String status,
        String state,
        int sampleRate,
        int channels,
        long bytes,
        long frames,
        double rms,
        int peak,
        long durationMs,
        String filePath,
        String error
    ) {
        return String.format(
            Locale.US,
            "status=%s;state=%s;sample_rate=%d;channels=%d;bytes=%d;frames=%d;rms=%.2f;peak=%d;duration_ms=%d;file_path=%s;error=%s",
            status, state, sampleRate, channels, bytes, frames, rms, peak, durationMs, filePath != null ? filePath : "NONE", error
        );
    }
}
