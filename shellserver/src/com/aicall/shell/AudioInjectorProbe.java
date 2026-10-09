package com.aicall.shell;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class AudioInjectorProbe {
    public static void main(String[] args) {
        System.out.println("=== PROBING AUDIO INJECTION CAPABILITIES (UID 2000) ===");
        
        // 1. Check AudioManager methods
        System.out.println("\n--- AudioManager Methods ---");
        for (Method m : AudioManager.class.getDeclaredMethods()) {
            String name = m.getName().toLowerCase();
            if (name.contains("uplink") || name.contains("inject") || name.contains("intercept") 
                    || name.contains("callaudio") || name.contains("stream") && name.contains("call")) {
                System.out.println("AudioManager." + m.getName() + " -> " + m.getReturnType().getSimpleName());
            }
        }

        // 2. Check AudioSystem methods
        System.out.println("\n--- AudioSystem Methods ---");
        try {
            Class<?> audioSystemClass = Class.forName("android.media.AudioSystem");
            for (Method m : audioSystemClass.getDeclaredMethods()) {
                String name = m.getName().toLowerCase();
                if (name.contains("param") || name.contains("force") || name.contains("route") 
                        || name.contains("device") || name.contains("phone") || name.contains("mode")) {
                    System.out.println("AudioSystem." + m.getName() + "(" + formatParams(m) + ")");
                }
            }
        } catch (Throwable t) {
            System.out.println("AudioSystem probe error: " + t);
        }

        // 3. Test AudioManager instantiation & getCallUplinkInjectionAudioTrack
        System.out.println("\n--- Test AudioManager & getCallUplinkInjectionAudioTrack ---");
        try {
            android.content.Context proxyContext = new android.content.ContextWrapper(null) {
                @Override public String getOpPackageName() { return "com.android.shell"; }
                @Override public String getPackageName() { return "com.android.shell"; }
                @Override public int getDeviceId() { return 0; }
                @Override public android.os.Looper getMainLooper() { return android.os.Looper.getMainLooper() != null ? android.os.Looper.getMainLooper() : android.os.Looper.myLooper(); }
                @Override public android.content.AttributionSource getAttributionSource() {
                    return new android.content.AttributionSource.Builder(android.os.Process.myUid())
                            .setPackageName("com.android.shell")
                            .build();
                }
                @Override public android.content.pm.ApplicationInfo getApplicationInfo() {
                    android.content.pm.ApplicationInfo ai = new android.content.pm.ApplicationInfo();
                    ai.packageName = "com.android.shell";
                    ai.uid = android.os.Process.myUid();
                    ai.targetSdkVersion = 34;
                    return ai;
                }
                @Override public android.content.Context getApplicationContext() { return this; }
            };

            System.out.println("Created proxyContext: " + proxyContext);
            AudioManager am = null;
            for (java.lang.reflect.Constructor<?> c : AudioManager.class.getDeclaredConstructors()) {
                if (c.getParameterCount() == 1 && c.getParameterTypes()[0] == android.content.Context.class) {
                    c.setAccessible(true);
                    am = (AudioManager) c.newInstance(proxyContext);
                    System.out.println("Instantiated AudioManager with proxyContext: " + am);
                    break;
                }
            }

            if (am != null) {
                try {
                    Method isInterceptable = am.getClass().getMethod("isPstnCallAudioInterceptable");
                    boolean interceptable = (boolean) isInterceptable.invoke(am);
                    System.out.println(">>> am.isPstnCallAudioInterceptable() = " + interceptable);
                } catch (Throwable t) {
                    System.out.println("isPstnCallAudioInterceptable error: " + t);
                }

                int[] testModes = new int[]{
                    AudioManager.MODE_IN_CALL,            // 2
                    AudioManager.MODE_IN_COMMUNICATION,   // 3
                    4                                     // MODE_CALL_SCREENING
                };
                String[] modeNames = new String[]{"MODE_IN_CALL (2)", "MODE_IN_COMMUNICATION (3)", "MODE_CALL_SCREENING (4)"};

                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(24000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build();

                for (int mIdx = 0; mIdx < testModes.length; mIdx++) {
                    int mode = testModes[mIdx];
                    String mName = modeNames[mIdx];
                    System.out.println("\n--- Testing with " + mName + " ---");
                    try {
                        am.setMode(mode);
                        System.out.println("Current mode: " + am.getMode());
                        Method getUplink = am.getClass().getMethod("getCallUplinkInjectionAudioTrack", AudioFormat.class);
                        AudioTrack track = (AudioTrack) getUplink.invoke(am, format);
                        System.out.println(">>> SUCCESS! Injected AudioTrack: " + track);
                        if (track != null) {
                            System.out.println("AudioAttributes: " + track.getAudioAttributes());
                            System.out.println("RoutedDevice: " + track.getRoutedDevice());
                            System.out.println("State=" + track.getState() + ", PlayState=" + track.getPlayState());
                            track.play();
                            System.out.println("After play(): PlayState=" + track.getPlayState());

                            // Test Muting stream 0 (STREAM_VOICE_CALL)
                            am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0);
                            System.out.println("STREAM_VOICE_CALL isMuted: " + am.isStreamMute(AudioManager.STREAM_VOICE_CALL));

                            byte[] testBytes = new byte[3200];
                            int written = track.write(testBytes, 0, testBytes.length);
                            System.out.println("Wrote bytes to uplink track while voice call stream muted: " + written);

                            am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0);
                            System.out.println("Unmuted STREAM_VOICE_CALL");

                            track.stop();
                            track.release();
                        }
                    } catch (Throwable t) {
                        Throwable cause = t.getCause() != null ? t.getCause() : t;
                        System.out.println(mName + " FAILED: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                    } finally {
                        try { am.setMode(AudioManager.MODE_NORMAL); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("Error testing injection: " + (t.getCause() != null ? t.getCause() : t));
            if (t.getCause() != null) t.getCause().printStackTrace();
            else t.printStackTrace();
        }
    }

    private static String formatParams(Method m) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> p : m.getParameterTypes()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.getSimpleName());
        }
        return sb.toString();
    }
}
