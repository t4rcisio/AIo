package com.aicall.shell;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Shell-side daemon executado pelo Android ART/Dalvik via app_process como UID shell (2000).
 * Abre um ServerSocket exclusivo em 127.0.0.1 (loopback) na porta 28472 e responde a PING e INFO.
 */
public class Main {
    public static final int PORT = 28472;

    public static void main(String[] args) {
        int uid = -1;
        int pid = -1;
        int sdk = -1;
        String model = "Unknown";

        try {
            uid = android.os.Process.myUid();
            pid = android.os.Process.myPid();
            sdk = android.os.Build.VERSION.SDK_INT;
            model = android.os.Build.MODEL;
        } catch (Throwable t) {
            System.err.println("[ShellServer] Aviso ao consultar Process/Build: " + t.getMessage());
        }

        System.out.println("[ShellServer] Iniciado com sucesso. UID=" + uid + ", PID=" + pid + ", SDK=" + sdk + ", Model=" + model);

        ServerSocket serverSocket = null;
        try {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new java.net.InetSocketAddress(loopback, PORT), 50);
            System.out.println("[ShellServer] Ouvindo conexões locais em 127.0.0.1:" + PORT);

            while (!serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    handleClient(clientSocket, uid, pid, sdk, model);
                } catch (Exception e) {
                    if (serverSocket.isClosed()) break;
                    System.err.println("[ShellServer] Erro ao aceitar conexão: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("[ShellServer] Falha ao abrir ServerSocket na porta " + PORT + ": " + e.getMessage());
            System.exit(1);
        } finally {
            if (serverSocket != null && !serverSocket.isClosed()) {
                try { serverSocket.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static void handleClient(Socket socket, int uid, int pid, int sdk, String model) {
        Thread thread = new Thread(() -> {
            try (Socket s = socket;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
                 PrintWriter writer = new PrintWriter(s.getOutputStream(), true)) {

                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.equalsIgnoreCase("PING")) {
                        writer.println("PONG");
                    } else if (line.equalsIgnoreCase("INFO")) {
                        writer.println("uid=" + uid + ";pid=" + pid + ";sdk=" + sdk + ";model=" + model + ";version=5;process=aicall-shellserver");
                    } else if (line.startsWith("AUDIO_TEST")) {
                        int duration = 5;
                        String[] parts = line.split("\\s+");
                        if (parts.length > 1) {
                            try {
                                duration = Integer.parseInt(parts[1]);
                            } catch (Throwable ignored) {}
                        }
                        String testResult = AudioTester.runTest(duration);
                        writer.println("AUDIO_TEST_RESULT:" + testResult);
                    } else if (line.equalsIgnoreCase("STREAM_CALL_AUDIO")) {
                        AudioStreamer.streamCallAudio(s, s.getOutputStream());
                        break;
                    } else if (line.startsWith("INJECT_CALL_AUDIO")) {
                        int sr = 24000;
                        String[] parts = line.split("\\s+");
                        if (parts.length > 1) {
                            try { sr = Integer.parseInt(parts[1]); } catch (Throwable ignored) {}
                        }
                        writer.println("INJECT_READY");
                        AudioInjector.handleInjectionStream(s, s.getInputStream(), sr);
                        break;
                    } else if (line.equalsIgnoreCase("MUTE_LOCAL_AUDIO")) {
                        AudioMuter.muteLocalAudio();
                        writer.println("MUTED");
                    } else if (line.equalsIgnoreCase("UNMUTE_LOCAL_AUDIO")) {
                        AudioMuter.unmuteLocalAudio();
                        writer.println("UNMUTED");
                    } else if (line.equalsIgnoreCase("QUIT")) {
                        writer.println("BYE");
                        break;
                    } else if (line.equalsIgnoreCase("SHUTDOWN")) {
                        writer.println("STOPPING");
                        System.exit(0);
                    } else {
                        writer.println("UNKNOWN_COMMAND");
                    }
                }
            } catch (Exception ignored) {
            }
        });
        thread.setDaemon(true);
        thread.start();
    }
}
