package com.xinghuiTec.redis;

import com.xinghuiTec.config.RedissonConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RedissonClient;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual starter/client against a loopback RESP fixture; never connects to an installed Redis or writes data. */
class RedissonPasswordStartupTest {

    @ParameterizedTest
    @NullAndEmptySource
    void absentOrEmptyEnvironmentPasswordStartsWithoutAuth(String password) throws Exception {
        try (RespFixture redis = new RespFixture(null, null)) {
            runner(redis, password).run(context -> {
                assertThat(context).hasNotFailed();
                RedissonClient client = context.getBean(RedissonClient.class);
                assertThat(client.getKeys().count()).isZero();
                assertThat(client.getConfig().isUseScriptCache()).isTrue();
                assertThat(redis.authCommands()).isEmpty();
                assertThat(redis.commands).contains(List.of("SELECT", "14"));
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"neutral-test-secret", " surrounding spaces ", " "})
    void nonemptyPasswordsAreAuthenticatedByteForByte(String password) throws Exception {
        try (RespFixture redis = new RespFixture(null, password)) {
            runner(redis, password).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(RedissonClient.class).getKeys().count()).isZero();
                assertThat(redis.authCommands()).isNotEmpty().allSatisfy(command ->
                        assertThat(command).containsExactly("AUTH", password));
            });
        }
    }

    @Test
    void aclUsernameAndPasswordRemainAuthenticated() throws Exception {
        try (RespFixture redis = new RespFixture("fixture-user", "neutral-test-secret")) {
            runner(redis, "neutral-test-secret").withPropertyValues("spring.data.redis.username=fixture-user")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(RedissonClient.class).getKeys().count()).isZero();
                        assertThat(redis.authCommands()).isNotEmpty().allSatisfy(command ->
                                assertThat(command).containsExactly("AUTH", "fixture-user", "neutral-test-secret"));
                    });
        }
    }

    @Test
    void incorrectNonemptyPasswordStillFailsStartup() throws Exception {
        try (RespFixture redis = new RespFixture(null, "neutral-test-secret")) {
            runner(redis, "wrong-test-secret").run(context -> {
                assertThat(context).hasFailed();
                assertThat(redis.authCommands()).isNotEmpty().allSatisfy(command ->
                        assertThat(command).containsExactly("AUTH", "wrong-test-secret"));
            });
        }
    }

    private ApplicationContextRunner runner(RespFixture redis, String password) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedissonAutoConfigurationV2.class, RedisAutoConfiguration.class))
                .withUserConfiguration(RedissonConfig.class, SmallPool.class)
                .withPropertyValues("REDIS_PORT=" + redis.port(), "REDIS_DATABASE=14")
                .withInitializer(context -> {
                    // Do not inherit any developer/production credentials; test the actual YAML fallback.
                    context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    try {
                        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
                            context.getEnvironment().getPropertySources().addLast(source);
                        }
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
        return password == null ? runner : runner.withInitializer(context ->
                context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("fixture-password", Map.of("REDIS_PASSWORD", password))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SmallPool {
        @Bean
        RedissonAutoConfigurationCustomizer smallTestPool() {
            return config -> {
                config.setThreads(1).setNettyThreads(2);
                config.useSingleServer().setConnectionPoolSize(2).setConnectionMinimumIdleSize(1)
                        .setSubscriptionConnectionPoolSize(1).setSubscriptionConnectionMinimumIdleSize(1)
                        .setConnectTimeout(1000).setTimeout(1000).setRetryAttempts(0).setPingConnectionInterval(0);
            };
        }
    }

    /** Only the startup/read commands used here; AUTH on an unprotected fixture deliberately returns Redis's error. */
    private static final class RespFixture implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "redis-password-fixture");
            thread.setDaemon(true);
            return thread;
        });
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final List<List<String>> commands = new CopyOnWriteArrayList<>();
        private final String username;
        private final String password;

        RespFixture(String username, String password) throws IOException {
            this.username = username;
            this.password = password;
            server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            workers.execute(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        sockets.add(socket);
                        workers.execute(() -> serve(socket));
                    } catch (IOException exception) {
                        if (!server.isClosed()) throw new IllegalStateException(exception);
                    }
                }
            });
        }

        int port() { return server.getLocalPort(); }

        List<List<String>> authCommands() {
            return commands.stream().filter(command -> command.get(0).equals("AUTH")).toList();
        }

        private void serve(Socket socket) {
            try (socket; var input = new BufferedInputStream(socket.getInputStream());
                 var output = new BufferedOutputStream(socket.getOutputStream())) {
                boolean authenticated = password == null;
                while (!socket.isClosed()) {
                    String header = line(input);
                    if (header == null) return;
                    if (!header.startsWith("*")) throw new IOException("Expected RESP array");
                    List<String> command = new ArrayList<>();
                    for (int count = Integer.parseInt(header.substring(1)); count > 0; count--) {
                        String length = line(input);
                        if (length == null || !length.startsWith("$")) throw new IOException("Expected bulk string");
                        int size = Integer.parseInt(length.substring(1));
                        byte[] bytes = input.readNBytes(size);
                        if (bytes.length != size || input.read() != '\r' || input.read() != '\n') throw new EOFException();
                        command.add(new String(bytes, StandardCharsets.UTF_8));
                    }
                    command.set(0, command.get(0).toUpperCase(Locale.ROOT));
                    commands.add(List.copyOf(command));
                    String response;
                    if (command.get(0).equals("AUTH")) {
                        if (password == null) {
                            response = "-ERR AUTH called without any password configured\r\n";
                        } else if (command.equals(username == null ? List.of("AUTH", password) : List.of("AUTH", username, password))) {
                            authenticated = true;
                            response = "+OK\r\n";
                        } else {
                            response = "-WRONGPASS invalid credentials\r\n";
                        }
                    } else if (!authenticated) {
                        response = "-NOAUTH Authentication required\r\n";
                    } else {
                        response = switch (command.get(0)) {
                            case "SELECT", "CLIENT" -> "+OK\r\n";
                            case "PING" -> "+PONG\r\n";
                            case "DBSIZE" -> ":0\r\n";
                            default -> "-ERR unsupported fixture command\r\n";
                        };
                    }
                    output.write(response.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
            } catch (IOException ignored) {
                // Redisson and fixture close their sockets during startup failure/shutdown.
            }
        }

        private static String line(BufferedInputStream input) throws IOException {
            StringBuilder value = new StringBuilder();
            for (int next; (next = input.read()) != -1;) {
                if (next == '\r') {
                    if (input.read() != '\n') throw new IOException("Invalid RESP line");
                    return value.toString();
                }
                value.append((char) next);
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket socket : sockets) socket.close();
            workers.shutdownNow();
        }
    }
}
