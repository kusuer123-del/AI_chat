package com.mdkj.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简 Redis 客户端：按 RESP 协议实现本项目用到的 PING / GET / SET / SETEX / DEL。
 *
 * <p>刻意不引 spring-boot-starter-data-redis，是为了让工程在"只有 JDK + Maven 缓存"的环境下也能编译运行；
 * 上生产时把这个类换成 RedisTemplate 即可，ConversationStore 边界不用动。</p>
 */
public class SimpleRedisClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(SimpleRedisClient.class);
    private static final byte[] CRLF = {'\r', '\n'};

    private final String host;
    private final int port;
    private final String password;
    private final int database;
    private final int timeoutMs;

    private Socket socket;
    private BufferedInputStream in;
    private BufferedOutputStream out;

    public SimpleRedisClient(String host, int port, String password, int database, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.database = database;
        this.timeoutMs = timeoutMs <= 0 ? 2000 : timeoutMs;
    }

    public String ping() {
        return asText(command("PING"));
    }

    public String get(String key) {
        return asText(command("GET", key));
    }

    public void set(String key, String value, Duration ttl) {
        if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
            command("SETEX", key, String.valueOf(Math.max(1, ttl.getSeconds())), value);
        } else {
            command("SET", key, value);
        }
    }

    public void delete(String key) {
        command("DEL", key);
    }

    @Override
    public void close() {
        closeQuietly();
    }

    private synchronized Object command(String... parts) {
        try {
            ensureConnected();
            write(parts);
            Object reply = readReply();
            if (reply instanceof RedisError error) {
                throw new IllegalStateException("Redis 返回错误: " + error.message());
            }
            return reply;
        } catch (IOException e) {
            closeQuietly();
            throw new IllegalStateException("Redis 通信失败(" + host + ":" + port + "): " + e.getMessage(), e);
        }
    }

    private void ensureConnected() throws IOException {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return;
        }
        Socket fresh = new Socket();
        fresh.connect(new InetSocketAddress(host, port), timeoutMs);
        fresh.setSoTimeout(timeoutMs);
        this.socket = fresh;
        this.in = new BufferedInputStream(fresh.getInputStream());
        this.out = new BufferedOutputStream(fresh.getOutputStream());

        if (password != null && !password.isBlank()) {
            write("AUTH", password);
            expectOk("AUTH");
        }
        if (database != 0) {
            write("SELECT", String.valueOf(database));
            expectOk("SELECT");
        }
    }

    private void expectOk(String operation) throws IOException {
        Object reply = readReply();
        if (reply instanceof RedisError error) {
            throw new IOException(operation + " 失败: " + error.message());
        }
    }

    private void write(String... parts) throws IOException {
        StringBuilder header = new StringBuilder("*").append(parts.length).append("\r\n");
        out.write(header.toString().getBytes(StandardCharsets.UTF_8));
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            out.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.write(CRLF);
        }
        out.flush();
    }

    private Object readReply() throws IOException {
        int type = in.read();
        if (type == -1) {
            throw new EOFException("Redis 连接已关闭");
        }
        String line = readLine();
        switch (type) {
            case '+':
                return line;
            case '-':
                return new RedisError(line);
            case ':':
                return Long.parseLong(line);
            case '$': {
                int length = Integer.parseInt(line);
                if (length < 0) {
                    return null;
                }
                byte[] payload = new byte[length];
                readFully(payload);
                readLine();
                return new String(payload, StandardCharsets.UTF_8);
            }
            case '*': {
                int size = Integer.parseInt(line);
                if (size < 0) {
                    return null;
                }
                List<Object> items = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    items.add(readReply());
                }
                return items;
            }
            default:
                throw new IOException("无法识别的 RESP 响应类型: " + (char) type);
        }
    }

    private String readLine() throws IOException {
        StringBuilder builder = new StringBuilder();
        int current;
        while ((current = in.read()) != -1) {
            if (current == '\r') {
                int next = in.read();
                if (next == '\n') {
                    break;
                }
                builder.append((char) current);
                if (next != -1) {
                    builder.append((char) next);
                }
                continue;
            }
            builder.append((char) current);
        }
        if (current == -1) {
            throw new EOFException("Redis 连接已关闭");
        }
        return builder.toString();
    }

    private void readFully(byte[] payload) throws IOException {
        int offset = 0;
        while (offset < payload.length) {
            int read = in.read(payload, offset, payload.length - offset);
            if (read == -1) {
                throw new EOFException("Redis 连接已关闭");
            }
            offset += read;
        }
    }

    private static String asText(Object reply) {
        if (reply == null) {
            return null;
        }
        return reply instanceof String text ? text : String.valueOf(reply);
    }

    private void closeQuietly() {
        Socket current = this.socket;
        this.socket = null;
        this.in = null;
        this.out = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException e) {
                log.debug("关闭 Redis 连接时出错: {}", e.getMessage());
            }
        }
    }

    private record RedisError(String message) {
    }
}