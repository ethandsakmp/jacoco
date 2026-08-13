package org.jacoco.agent.rt.internal.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public final class PortUtil {

    private static final long FIREWALLD_RESTART_TIMEOUT_SECONDS = 30;

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
            addPort(portSpec, permanent);
        } catch (IOException e) {
            try {
                restartFirewalld();
                addPort(portSpec, permanent);
            } catch (IOException retryEx) {
                throw new IllegalStateException(
                        "Failed to allowlist port " + portSpec
                                + " after firewalld restart (needs firewalld + root/sudo): "
                                + retryEx.getMessage(),
                        retryEx);
            }
        }
    }

    private static void addPort(String portSpec, boolean permanent) throws IOException {
        runCommand("firewall-cmd", "--add-port=" + portSpec);
        if (permanent) {
            runCommand("firewall-cmd", "--permanent", "--add-port=" + portSpec);
        }
    }

    private static void restartFirewalld() throws IOException {
        runCommand(FIREWALLD_RESTART_TIMEOUT_SECONDS, "sudo", "systemctl", "restart", "firewalld");
    }

    private static void runCommand(String... command) throws IOException {
        runCommand(0, command);
    }

    private static void runCommand(long timeoutSeconds, String... command) throws IOException {
        Process p;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException ioe) {
            throw new IOException("Failed to start command: " + Arrays.toString(command), ioe);
        }

        try {
            final int exit;
            final String output;
            if (timeoutSeconds > 0) {
                // Wait first so the timeout is enforced even if stdout stays open.
                if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    throw new IOException("Command timed out after " + timeoutSeconds + "s: "
                            + Arrays.toString(command));
                }
                exit = p.exitValue();
                output = readAll(p);
            } else {
                output = readAll(p);
                exit = p.waitFor();
            }
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
