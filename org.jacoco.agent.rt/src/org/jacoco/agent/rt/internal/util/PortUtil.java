package org.jacoco.agent.rt.internal.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.util.Arrays;

public final class PortUtil {

    private PortUtil() {}

    public static int findAndAllowlistPort() {
        int port = findAvailablePort();
        allowlistPort(port, "tcp", false); // runtime only
        return port;
    }

    public static int findAndAllowlistPortPermanent() {
        int port = findAvailablePort();
        allowlistPort(port, "tcp", true);  // runtime + permanent
        return port;
    }

    public static int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to find an available port", e);
        }
    }

    private static void allowlistPort(int port, String protocol, boolean permanent) {
        String portSpec = port + "/" + protocol;

        try {
            runCommand("firewall-cmd", "--add-port=" + portSpec);

            if (permanent) {
                runCommand("firewall-cmd", "--permanent", "--add-port=" + portSpec);
                // optional: make permanent rules effective immediately
                // runCommand("firewall-cmd", "--reload");
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to allowlist port " + portSpec + " (needs firewalld + root/sudo): " + e.getMessage(), e);
        }
    }

    private static void runCommand(String... command) throws IOException {
        Process p;
        try {
            p = new ProcessBuilder(command).start();
        } catch (IOException ioe) {
            throw new IOException("Failed to start command: " + Arrays.toString(command), ioe);
        }

        String output = readAll(p);

        try {
            int exit = p.waitFor();
            if (exit != 0) {
                throw new IOException("Command failed (rc=" + exit + "): " + Arrays.toString(command) +
                        (output.isEmpty() ? "" : "\n" + output));
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Command interrupted: " + Arrays.toString(command), ie);
        }
    }

    private static String readAll(Process p) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException ignored) {
            // ignore read errors; command exit code will still be checked
        }
        return sb.toString().trim();
    }
}
