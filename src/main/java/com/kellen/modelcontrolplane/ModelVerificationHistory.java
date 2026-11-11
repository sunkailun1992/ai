package com.kellen.modelcontrolplane;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kellen.modelcontrolplane.ModelControlPlaneResponse.Capability;
import com.kellen.modelcontrolplane.ModelControlPlaneResponse.Verification;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;

/** 只读本机历史验证证据；文件不可用时保持模型目录可读。 */
final class ModelVerificationHistory {
    private static final int MAX_FILE_BYTES = 128 * 1024;
    private static final int MAX_ENTRIES = 256;
    private static final Set<PosixFilePermission> OWNER_PERMISSIONS =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    static {
        JSON.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(5).maxStringLength(512).build());
    }

    private ModelVerificationHistory() { }

    record Identity(String deploymentId, String modelName, String model, Capability capability) { }
    record Evidence(Verification status, Instant verifiedAt) { }

    static Map<Identity, Evidence> read(String configuredFile) {
        if (configuredFile.isBlank()) return Map.of();
        try {
            Path path = Path.of(configuredFile);
            if (!path.isAbsolute() || !path.equals(path.normalize())) return Map.of();
            for (Path part = path; part != null; part = part.getParent()) {
                if (Files.isSymbolicLink(part)) return Map.of();
            }
            var before = restrictedAttributes(path);
            byte[] bytes;
            try (var input = Files.newInputStream(path, READ, NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            }
            var after = restrictedAttributes(path);
            if (bytes.length > MAX_FILE_BYTES || before.size() != bytes.length || after.size() != bytes.length
                    || !before.fileKey().equals(after.fileKey())
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())) return Map.of();
            return decode(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
        } catch (IOException | RuntimeException invalid) {
            // 配置路径、文件内容和解析异常都留在服务端，不进入目录响应或日志。
            return Map.of();
        }
    }

    private static PosixFileAttributes restrictedAttributes(Path path) throws IOException {
        var attributes = Files.readAttributes(path, PosixFileAttributes.class, NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.fileKey() == null
                || !attributes.owner().getName().equals(System.getProperty("user.name"))
                || !attributes.permissions().contains(PosixFilePermission.OWNER_READ)
                || !OWNER_PERMISSIONS.containsAll(attributes.permissions())
                || attributes.size() > MAX_FILE_BYTES
                || !Integer.valueOf(1).equals(Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS))) {
            throw new IOException();
        }
        return attributes;
    }

    private static Map<Identity, Evidence> decode(String payload) throws IOException {
        var root = JSON.readTree(payload);
        if (root == null || !root.isObject() || root.size() != 1) return Map.of();
        var entries = root.path("entries");
        if (!entries.isArray() || entries.size() > MAX_ENTRIES) return Map.of();
        Map<Identity, Evidence> history = new HashMap<>();
        for (JsonNode row : entries) {
            if (!row.isObject() || row.size() != 6) return Map.of();
            var identity = new Identity(text(row, "deploymentId", 128), text(row, "modelName", 200),
                    text(row, "model", 200), Capability.valueOf(text(row, "capability", 32)));
            var status = Verification.valueOf(text(row, "verificationStatus", 16));
            if (status == Verification.NOT_CHECKED) return Map.of();
            var evidence = new Evidence(status, Instant.parse(text(row, "verifiedAt", 64)));
            if (history.putIfAbsent(identity, evidence) != null) return Map.of();
        }
        return Map.copyOf(history);
    }

    private static String text(JsonNode row, String field, int maxCharacters) {
        var node = row.path(field);
        if (!node.isTextual()) throw new IllegalArgumentException();
        String value = node.textValue();
        if (value.isBlank() || value.codePointCount(0, value.length()) > maxCharacters
                || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        return value;
    }
}
