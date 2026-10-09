package com.aicall.shell;

import java.lang.reflect.Method;

/**
 * Utilitário responsável pelo silenciamento acústico total do aparelho local
 * (alto-falante principal e alto-falante menor/earpiece) durante chamadas atendidas pela IA.
 */
public class AudioMuter {

    public static void muteLocalAudio() {
        System.out.println("[AudioMuter] Silenciando completamente áudio local (alto-falante e earpiece)...");
        try {
            // 1. Muta stream telefônico (STREAM_VOICE_CALL = 0) via comando oficial do AudioService
            // NOTA CRÍTICA: NUNCA chamar set-volume aqui, pois o AudioService desmuta streams ao alterar volume!
            Runtime.getRuntime().exec(new String[]{"cmd", "audio", "adj-mute", "0"}).waitFor();

            // 2. Muta streams secundários (STREAM_SYSTEM = 1, STREAM_MUSIC = 3, STREAM_NOTIFICATION = 5)
            Runtime.getRuntime().exec(new String[]{"cmd", "audio", "adj-mute", "3"}).waitFor();
            Runtime.getRuntime().exec(new String[]{"cmd", "audio", "adj-mute", "1"}).waitFor();
            Runtime.getRuntime().exec(new String[]{"cmd", "audio", "adj-mute", "5"}).waitFor();

            // 3. Parâmetros específicos da HAL de áudio (Audio HAL Vendor Parameters)
            try {
                Class<?> audioSystem = Class.forName("android.media.AudioSystem");
                Method setParameters = audioSystem.getMethod("setParameters", String.class);
                setParameters.invoke(null, "voice_earpiece_mute=true");
                setParameters.invoke(null, "voice_rx_mute=true");
                setParameters.invoke(null, "sidetone=0");
            } catch (Throwable ignored) {}

            System.out.println("[AudioMuter] Mute local total aplicado com sucesso.");
        } catch (Throwable t) {
            System.err.println("[AudioMuter] Erro ao aplicar mute: " + t.getMessage());
        }
    }

    public static void unmuteLocalAudio() {
        System.out.println("[AudioMuter] Restaurando áudio do aparelho...");
        try {
            Runtime.getRuntime().exec(new String[]{"cmd", "audio", "adj-unmute", "0"}).waitFor();
            Runtime.getRuntime().exec(new String[]{"cmd", "audio", "adj-unmute", "3"}).waitFor();
            Runtime.getRuntime().exec(new String[]{"cmd", "audio", "adj-unmute", "1"}).waitFor();
            Runtime.getRuntime().exec(new String[]{"cmd", "audio", "adj-unmute", "5"}).waitFor();

            try {
                Class<?> audioSystem = Class.forName("android.media.AudioSystem");
                Method setParameters = audioSystem.getMethod("setParameters", String.class);
                setParameters.invoke(null, "voice_earpiece_mute=false");
                setParameters.invoke(null, "voice_rx_mute=false");
            } catch (Throwable ignored) {}

            System.out.println("[AudioMuter] Desmute aplicado com sucesso.");
        } catch (Throwable t) {
            System.err.println("[AudioMuter] Erro ao desmutar áudio: " + t.getMessage());
        }
    }
}
