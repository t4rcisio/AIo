package com.aicall.shell;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Looper;

import java.io.OutputStream;
import java.net.Socket;

/**
 * Transmissor de áudio PCM contínuo com separação de canais (UPLINK = Você, DOWNLINK = Interlocutor).
 * O Shell Daemon (UID 2000) captura independentemente os dois lados da chamada e os envia ao app.
 */
public class AudioStreamer {

    public static final byte TRACK_UPLINK = 1;   // Você / Quem fala no aparelho (Uplink)
    public static final byte TRACK_DOWNLINK = 2; // Interlocutor / Quem fala remotamente (Downlink)

    public static void streamCallAudio(Socket socket, OutputStream os) {
        int sampleRate = 16000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

        AudioRecord uplinkRec = null;
        AudioRecord downlinkRec = null;

        try {
            if (Looper.myLooper() == null) {
                try { Looper.prepare(); } catch (Throwable ignored) {}
            }

            int minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
            int bufSize = Math.max(minBuf * 2, 8192);

            try {
                uplinkRec = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_UPLINK,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufSize
                );
            } catch (Throwable t) {
                System.err.println("[AudioStreamer] Erro ao instanciar VOICE_UPLINK: " + t.getMessage());
            }

            try {
                downlinkRec = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_DOWNLINK,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufSize
                );
            } catch (Throwable t) {
                System.err.println("[AudioStreamer] Erro ao instanciar VOICE_DOWNLINK: " + t.getMessage());
            }

            boolean hasUplink = (uplinkRec != null && uplinkRec.getState() == AudioRecord.STATE_INITIALIZED);
            boolean hasDownlink = (downlinkRec != null && downlinkRec.getState() == AudioRecord.STATE_INITIALIZED);

            if (!hasUplink && !hasDownlink) {
                streamFallbackVoiceCall(socket, os, sampleRate, channelConfig, audioFormat, bufSize);
                return;
            }

            if (hasUplink) {
                uplinkRec.startRecording();
            }
            if (hasDownlink) {
                downlinkRec.startRecording();
            }

            // Garante silenciamento total do aparelho local (alto-falante e earpiece)
            AudioMuter.muteLocalAudio();

            os.write("STREAM_STARTED;mode=separated\n".getBytes());
            os.flush();

            final Object writeLock = new Object();
            final AudioRecord fUplink = uplinkRec;
            final AudioRecord fDownlink = downlinkRec;

            Thread uplinkThread = null;
            if (hasUplink) {
                uplinkThread = new Thread(() -> {
                    byte[] buffer = new byte[3200]; // 100ms de áudio
                    byte[] packet = new byte[3 + 3200];
                    packet[0] = TRACK_UPLINK;
                    while (!socket.isClosed() && socket.isConnected()) {
                        int read = fUplink.read(buffer, 0, buffer.length);
                        if (read > 0) {
                            packet[1] = (byte) ((read >> 8) & 0xFF);
                            packet[2] = (byte) (read & 0xFF);
                            System.arraycopy(buffer, 0, packet, 3, read);
                            synchronized (writeLock) {
                                try {
                                    os.write(packet, 0, 3 + read);
                                    os.flush();
                                } catch (Throwable ignored) {
                                    break;
                                }
                            }
                        } else if (read < 0) {
                            break;
                        }
                    }
                }, "UplinkStreamThread");
                uplinkThread.setDaemon(true);
                uplinkThread.start();
            }

            Thread downlinkThread = null;
            if (hasDownlink) {
                downlinkThread = new Thread(() -> {
                    byte[] buffer = new byte[3200]; // 100ms de áudio
                    byte[] packet = new byte[3 + 3200];
                    packet[0] = TRACK_DOWNLINK;
                    while (!socket.isClosed() && socket.isConnected()) {
                        int read = fDownlink.read(buffer, 0, buffer.length);
                        if (read > 0) {
                            packet[1] = (byte) ((read >> 8) & 0xFF);
                            packet[2] = (byte) (read & 0xFF);
                            System.arraycopy(buffer, 0, packet, 3, read);
                            synchronized (writeLock) {
                                try {
                                    os.write(packet, 0, 3 + read);
                                    os.flush();
                                } catch (Throwable ignored) {
                                    break;
                                }
                            }
                        } else if (read < 0) {
                            break;
                        }
                    }
                }, "DownlinkStreamThread");
                downlinkThread.setDaemon(true);
                downlinkThread.start();
            }

            if (uplinkThread != null) {
                try { uplinkThread.join(); } catch (InterruptedException ignored) {}
            }
            if (downlinkThread != null) {
                try { downlinkThread.join(); } catch (InterruptedException ignored) {}
            }

        } catch (Throwable t) {
            System.err.println("[AudioStreamer] Erro no streaming de áudio: " + t.getMessage());
        } finally {
            AudioMuter.unmuteLocalAudio();
            if (uplinkRec != null) {
                try { uplinkRec.stop(); } catch (Throwable ignored) {}
                try { uplinkRec.release(); } catch (Throwable ignored) {}
            }
            if (downlinkRec != null) {
                try { downlinkRec.stop(); } catch (Throwable ignored) {}
                try { downlinkRec.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void streamFallbackVoiceCall(Socket socket, OutputStream os, int sampleRate, int channelConfig, int audioFormat, int bufSize) {
        AudioRecord record = null;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_CALL, sampleRate, channelConfig, audioFormat, bufSize);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                os.write("ERROR:AudioRecord_NOT_INITIALIZED\n".getBytes());
                os.flush();
                return;
            }
            record.startRecording();
            os.write("STREAM_STARTED;mode=mixed\n".getBytes());
            os.flush();

            byte[] buffer = new byte[3200];
            byte[] packet = new byte[3 + 3200];
            packet[0] = TRACK_UPLINK;
            while (!socket.isClosed() && socket.isConnected()) {
                int read = record.read(buffer, 0, buffer.length);
                if (read > 0) {
                    packet[1] = (byte) ((read >> 8) & 0xFF);
                    packet[2] = (byte) (read & 0xFF);
                    System.arraycopy(buffer, 0, packet, 3, read);
                    os.write(packet, 0, 3 + read);
                    os.flush();
                } else if (read < 0) {
                    break;
                }
            }
        } catch (Throwable t) {
            System.err.println("[AudioStreamer] Erro no fallback VOICE_CALL: " + t.getMessage());
        } finally {
            if (record != null) {
                try { record.stop(); } catch (Throwable ignored) {}
                try { record.release(); } catch (Throwable ignored) {}
            }
        }
    }
}
