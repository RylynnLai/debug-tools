package com.debugtools.desktop;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class ConnectionScanner {
    private static final int DEFAULT_PORT = 4939;

    private ConnectionScanner() {
    }

    static List<DiscoveredEndpoint> scan(
        int preferredPort,
        int timeoutMs,
        List<SeedEndpoint> seededEndpoints,
        String manualHost
    ) {
        Set<Endpoint> candidates = new LinkedHashSet<>();
        addSeededCandidates(candidates, seededEndpoints);
        addManualHostCandidates(candidates, manualHost, preferredPort);
        addLocalCandidates(candidates, preferredPort);
        addLanCandidates(candidates, preferredPort);

        List<DiscoveredEndpoint> found = new ArrayList<>();
        int workerCount = Math.min(48, Math.max(8, Runtime.getRuntime().availableProcessors() * 2));
        ExecutorService probeExecutor = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "debug-scan");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Callable<DiscoveredEndpoint>> jobs = new ArrayList<>();
            for (Endpoint endpoint : candidates) {
                jobs.add(() -> probe(endpoint, timeoutMs));
            }
            List<Future<DiscoveredEndpoint>> futures = probeExecutor.invokeAll(jobs);
            for (Future<DiscoveredEndpoint> future : futures) {
                try {
                    DiscoveredEndpoint endpoint = future.get();
                    if (endpoint != null) {
                        found.add(endpoint);
                    }
                } catch (ExecutionException ignored) {
                    // Ignore unreachable or non-DebugKit endpoints.
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            probeExecutor.shutdownNow();
        }

        found.sort(
            Comparator
                .comparing((DiscoveredEndpoint endpoint) -> endpoint.localhost ? 0 : 1)
                .thenComparing(endpoint -> endpoint.source)
                .thenComparing(endpoint -> endpoint.host)
                .thenComparingInt(endpoint -> endpoint.port)
        );
        return found;
    }

    private static DiscoveredEndpoint probe(Endpoint endpoint, int timeoutMs) {
        try {
            DebugClient.ProbeResult hello = DebugClient.probe(endpoint.host, endpoint.port, timeoutMs);
            return new DiscoveredEndpoint(
                endpoint.host,
                endpoint.port,
                hello.appPackage,
                hello.serverHost,
                hello.serverPort,
                endpoint.localhost,
                endpoint.source
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void addSeededCandidates(Set<Endpoint> candidates, List<SeedEndpoint> seededEndpoints) {
        if (seededEndpoints == null) return;
        for (SeedEndpoint endpoint : seededEndpoints) {
            if (endpoint == null || endpoint.host == null || endpoint.host.isBlank()) {
                continue;
            }
            candidates.add(new Endpoint(endpoint.host, endpoint.port, isLocalHost(endpoint.host), endpoint.source));
        }
    }

    private static void addManualHostCandidates(Set<Endpoint> candidates, String manualHost, int preferredPort) {
        if (manualHost == null || manualHost.isBlank()) return;
        String host = manualHost.trim();
        boolean localhost = isLocalHost(host);
        candidates.add(new Endpoint(host, preferredPort, localhost, localhost ? "manual-local" : "manual-lan"));
        if (preferredPort != DEFAULT_PORT) {
            candidates.add(new Endpoint(host, DEFAULT_PORT, localhost, localhost ? "manual-local" : "manual-lan"));
        }
    }

    private static void addLocalCandidates(Set<Endpoint> candidates, int preferredPort) {
        int start = Math.max(1, preferredPort - 3);
        int end = preferredPort + 6;
        for (int port = start; port <= end; port++) {
            candidates.add(new Endpoint("127.0.0.1", port, true, "local-scan"));
        }
        candidates.add(new Endpoint("127.0.0.1", DEFAULT_PORT, true, "local-scan"));
    }

    private static void addLanCandidates(Set<Endpoint> candidates, int preferredPort) {
        List<String> ownIps = localIpv4Addresses();
        for (String ownIp : ownIps) {
            int lastDot = ownIp.lastIndexOf('.');
            if (lastDot <= 0) continue;
            String prefix = ownIp.substring(0, lastDot + 1);
            for (int i = 1; i <= 254; i++) {
                String host = prefix + i;
                if (host.equals(ownIp)) continue;
                candidates.add(new Endpoint(host, preferredPort, false, "lan-scan"));
                if (preferredPort != DEFAULT_PORT) {
                    candidates.add(new Endpoint(host, DEFAULT_PORT, false, "lan-scan"));
                }
            }
        }
    }

    private static boolean isLocalHost(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
    }

    private static List<String> localIpv4Addresses() {
        List<String> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress address = inetAddresses.nextElement();
                    if (!(address instanceof Inet4Address) || !address.isSiteLocalAddress()) {
                        continue;
                    }
                    addresses.add(address.getHostAddress());
                }
            }
        } catch (Exception ignored) {
            // Keep scan resilient even when interface lookup fails.
        }
        return addresses;
    }

    static final class DiscoveredEndpoint {
        final String host;
        final int port;
        final String appPackage;
        final String serverHost;
        final int serverPort;
        final boolean localhost;
        final String source;

        private DiscoveredEndpoint(
            String host,
            int port,
            String appPackage,
            String serverHost,
            int serverPort,
            boolean localhost,
            String source
        ) {
            this.host = host;
            this.port = port;
            this.appPackage = appPackage;
            this.serverHost = serverHost;
            this.serverPort = serverPort;
            this.localhost = localhost;
            this.source = source;
        }
    }

    static final class SeedEndpoint {
        final String host;
        final int port;
        final String source;

        SeedEndpoint(String host, int port, String source) {
            this.host = host;
            this.port = port;
            this.source = source == null || source.isBlank() ? "seed" : source;
        }
    }

    private static final class Endpoint {
        private final String host;
        private final int port;
        private final boolean localhost;
        private final String source;

        private Endpoint(String host, int port, boolean localhost, String source) {
            this.host = host;
            this.port = port;
            this.localhost = localhost;
            this.source = source;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Endpoint endpoint)) return false;
            return port == endpoint.port && host.equals(endpoint.host);
        }

        @Override
        public int hashCode() {
            int result = host.hashCode();
            result = 31 * result + port;
            return result;
        }
    }
}

