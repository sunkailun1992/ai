package com.kellen.file;

import com.kellen.config.AiProperties;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 多模态媒体输入校验器。
 *
 * <p>AI 对话接口只接收远程附件引用，不接收 multipart 文件流。所有安全校验集中在本类完成：
 * 媒体数量上限、URL 协议白名单、远程 Content-Type 校验和 MIME 白名单。
 * 校验逻辑从 AiDialogueService 拆出后，对话编排与安全边界各自单一职责。</p>
 */
@Component // 注册为 Spring 组件，供对话服务构造器注入。
public class MediaInputValidator {

    private static final int MAX_TEXT_ATTACHMENT_CHARS = 12000; // 文本类附件拼入本轮消息的最大字符数，避免撑爆上下文。
    private static final int MAX_REDIRECTS = 5; // 远程媒体 URL 最多跟随跳转次数，防止跳转环。

    private final int maxMediaCount; // 单次请求允许的最大媒体数量，控制模型成本和请求体复杂度。
    private final long maxMediaBytes; // 单个二进制媒体读取上限，避免私有 URL 读取撑爆内存。
    private final Set<String> allowedSchemes; // URL 协议白名单，拒绝 file、classpath 等本地协议。
    private final Set<String> allowedMimeTypes; // 媒体 MIME 白名单，支持 image/* 这类通配前缀。

    /**
     * 从类型安全配置读取多模态安全限制。
     *
     * @param properties ai.* 配置绑定对象，使用其中 dialogue 段的媒体限制。
     */
    public MediaInputValidator(AiProperties properties) {
        this.maxMediaCount = Math.max(properties.dialogue().maxMediaCount(), 1); // 上限至少为 1，避免配置错误导致多模态完全不可用。
        this.maxMediaBytes = Math.max(properties.dialogue().maxMediaBytes(), 1024 * 1024); // 至少允许 1MB，避免错误配置导致图片全被拒绝。
        this.allowedSchemes = properties.dialogue().allowedMediaSchemes().stream() // 读取协议白名单配置。
                .filter(StringUtils::hasText) // 忽略空配置项。
                .map(scheme -> scheme.toLowerCase(Locale.ROOT)) // 协议名统一小写，避免大小写绕过。
                .collect(Collectors.toUnmodifiableSet()); // 生成不可变 Set，运行期不可被修改。
        this.allowedMimeTypes = properties.dialogue().allowedMediaMimeTypes().stream() // 读取 MIME 白名单配置。
                .filter(StringUtils::hasText) // 忽略空配置项。
                .map(value -> value.toLowerCase(Locale.ROOT)) // MIME 统一小写，避免大小写绕过。
                .collect(Collectors.toUnmodifiableSet()); // 生成不可变 Set，运行期不可被修改。
    }

    /**
     * 校验远程媒体 URL 列表并转换为可直接交给 Spring AI 的资源对象。
     *
     * <p>媒体 URL 列表是可选的；一旦提交，数量、协议和 MIME 都必须满足白名单。</p>
     *
     * @param media 调用方提交的媒体输入列表。
     * @return 校验通过的媒体资源列表，顺序与入参一致。
     */
    public List<ValidatedMedia> validateUrlMedia(List<MediaInput> media) {
        if (media == null || media.isEmpty()) { // 纯文本对话允许不携带媒体。
            return List.of();
        }
        validateMediaCount(media.size()); // 数量超限直接拒绝，不做截断，避免静默丢弃调用方数据。
        return media.stream().map(this::validateUrlItem).toList(); // 逐项校验 URL 协议和 MIME 类型。
    }

    /**
     * 生成媒体列表的可读摘要，用于审计落库。
     *
     * @param media 已通过安全校验的媒体输入列表。
     * @return 每行一条媒体摘要；无媒体时返回 null，避免审计表写入空串噪音。
     */
    public String summarize(List<ValidatedMedia> media) {
        if (media == null || media.isEmpty()) { // 无媒体输入。
            return null;
        }
        return media.stream() // 遍历已校验媒体输入。
                .map(input -> input.mimeType() + "|" + input.sourceSummary()) // 每项记录 MIME 和安全摘要，便于排查问题。
                .collect(Collectors.joining("\n")); // 多项使用换行分隔。
    }

    /**
     * 校验单个媒体输入并构造资源对象。
     *
     * @param input 单个媒体输入。
     * @return 校验通过的媒体资源。
     */
    private ValidatedMedia validateUrlItem(MediaInput input) {
        URI uri = URI.create(requiredText(input.url(), "media.url")); // 解析媒体 URL，并要求不能为空。
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT); // 提取并归一化 URL 协议。
        if (!allowedSchemes.contains(scheme)) { // 协议白名单校验，拒绝 file、classpath、ftp、jar 等非预期协议。
            throw new IllegalArgumentException("unsupported media url scheme: " + scheme);
        }
        try {
            MimeType mimeType = validateMimeType(input.mimeType(), "media.mimeType"); // 解析并检查 MIME 白名单。
            validateRemoteContentType(uri, mimeType); // 远程 URL 必须返回和声明一致的 Content-Type，避免把 HTML 分享页当图片交给模型。
            String textContent = remoteTextContent(uri, mimeType); // JSON / 文本类 URL 直接读入本轮文本上下文。
            Resource resource = textContent == null ? remoteBinaryResource(uri, mimeType) : null; // 非文本媒体由后端读成字节，避免模型无法访问私有或本地 URL。
            long sizeBytes = resource instanceof SizedByteArrayResource sizedResource ? sizedResource.sizeBytes() : 0; // 审计记录保留读取字节数。
            return new ValidatedMedia(mimeType, resource, "url:" + uri, sizeBytes, textContent); // URL 媒体只保存来源摘要，不落原文。
        } catch (IOException exception) {
            throw new IllegalArgumentException("failed to read media url content");
        }
    }

    /**
     * 校验媒体数量上限。
     *
     * @param count 本轮媒体数量。
     */
    private void validateMediaCount(int count) {
        if (count > maxMediaCount) { // 数量超限直接拒绝，不做截断，避免静默丢弃调用方数据。
            throw new IllegalArgumentException("media count exceeds limit: " + maxMediaCount);
        }
    }

    /**
     * 解析 MIME 并检查白名单，支持 image/* 这类通配规则。
     *
     * @param value     调用方提交的 MIME 字符串。
     * @param fieldName 字段名，用于错误提示。
     * @return 解析后的 MIME 类型。
     */
    private MimeType validateMimeType(String value, String fieldName) {
        MimeType mimeType = MimeType.valueOf(requiredText(value, fieldName)); // MIME 不能为空且必须符合标准格式。
        String normalized = mimeType.toString().toLowerCase(Locale.ROOT); // 统一小写用于白名单匹配。
        boolean allowed = allowedMimeTypes.contains("*/*") || allowedMimeTypes.contains(normalized)
                || allowedMimeTypes.stream()
                .filter(rule -> rule.endsWith("/*"))
                .anyMatch(rule -> normalized.startsWith(rule.substring(0, rule.length() - 1)));
        if (!allowed) { // 不在白名单内的媒体类型不交给模型。
            throw new IllegalArgumentException("unsupported media mime type: " + mimeType);
        }
        return mimeType;
    }

    /**
     * 校验远程 URL 实际 Content-Type 和调用方声明一致。
     *
     * @param uri              远程 URL。
     * @param declaredMimeType 调用方声明 MIME。
     */
    private void validateRemoteContentType(URI uri, MimeType declaredMimeType) {
        try {
            HttpURLConnection connection = openConnectionFollowingRedirects(uri, "HEAD", 5000);
            int status = connection.getResponseCode();
            String contentType = connection.getContentType();
            connection.disconnect();
            if (status >= 400) {
                throw new IllegalArgumentException("media url is not reachable: " + status);
            }
            if (!StringUtils.hasText(contentType)) {
                return;
            }
            MimeType actualMimeType = MimeType.valueOf(contentType);
            if (!isCompatibleMime(declaredMimeType, actualMimeType)) {
                throw new IllegalArgumentException("media url content type mismatch: declared "
                        + declaredMimeType + " but remote returned " + actualMimeType);
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IllegalArgumentException("failed to inspect media url content type");
        }
    }

    /**
     * 判断声明 MIME 与远程实际 MIME 是否兼容。
     *
     * @param declared 调用方声明 MIME。
     * @param actual   远程实际 MIME。
     * @return 兼容返回 true。
     */
    private boolean isCompatibleMime(MimeType declared, MimeType actual) {
        if (!declared.getType().equalsIgnoreCase(actual.getType())) {
            return false;
        }
        return "*".equals(declared.getSubtype()) || declared.getSubtype().equalsIgnoreCase(actual.getSubtype());
    }

    /**
     * 文本类 URL 附件读取为 UTF-8 字符串，由对话服务拼入本轮消息。
     *
     * @param mimeType 已解析 MIME。
     * @param uri      远程 URL。
     * @return 文本内容；非文本附件返回 null。
     */
    private String remoteTextContent(URI uri, MimeType mimeType) throws IOException {
        if (!isTextLike(mimeType)) {
            return null;
        }
        HttpURLConnection connection = openConnectionFollowingRedirects(uri, "GET", 5000);
        try {
            int status = connection.getResponseCode();
            if (status >= 400) {
                throw new IllegalArgumentException("media url is not reachable: " + status);
            }
            try (InputStream inputStream = connection.getInputStream()) {
                byte[] bytes = inputStream.readNBytes((MAX_TEXT_ATTACHMENT_CHARS * 4) + 1);
                String content = new String(bytes, StandardCharsets.UTF_8).trim();
                if (content.length() <= MAX_TEXT_ATTACHMENT_CHARS) {
                    return content;
                }
                return content.substring(0, MAX_TEXT_ATTACHMENT_CHARS) + "\n...[附件内容已截断]";
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 读取远程二进制附件为内存资源。
     *
     * <p>Spring AI 交给模型时使用字节内容，而不是让模型服务自行访问 URL。
     * 这样本地调试地址、后端 token URL、对象存储 302 短签名 URL 都可以用于多模态输入。</p>
     *
     * @param uri      已校验远程 URL。
     * @param mimeType 已校验 MIME 类型。
     * @return 可交给模型的字节资源。
     */
    private Resource remoteBinaryResource(URI uri, MimeType mimeType) throws IOException {
        HttpURLConnection connection = openConnectionFollowingRedirects(uri, "GET", 15000);
        try {
            int status = connection.getResponseCode();
            if (status >= 400) {
                throw new IllegalArgumentException("media url is not reachable: " + status);
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > maxMediaBytes) {
                throw new IllegalArgumentException("media file exceeds byte limit: " + maxMediaBytes);
            }
            try (InputStream inputStream = connection.getInputStream()) {
                byte[] bytes = inputStream.readNBytes((int) Math.min(Integer.MAX_VALUE, maxMediaBytes + 1));
                if (bytes.length > maxMediaBytes) {
                    throw new IllegalArgumentException("media file exceeds byte limit: " + maxMediaBytes);
                }
                if (bytes.length == 0) {
                    throw new IllegalArgumentException("media url returned empty content");
                }
                return new SizedByteArrayResource(bytes, safeFilename(uri, mimeType));
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 打开远程连接并显式跟随 HTTP 跳转。
     *
     * <p>JDK 的自动跳转在跨协议、带签名 URL 或部分 HEAD 场景下不稳定；这里显式处理
     * 301/302/303/307/308，确保后端 token URL 跳转到 OSS 短时签名 URL 后仍能读到真实文件。</p>
     *
     * @param uri         起始 URL。
     * @param method      HTTP 方法。
     * @param readTimeout 读取超时时间，单位毫秒。
     * @return 最终非跳转响应连接；调用方负责 disconnect。
     */
    private HttpURLConnection openConnectionFollowingRedirects(URI uri, String method, int readTimeout) throws IOException {
        URI current = uri;
        for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
            HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(readTimeout);
            int status = connection.getResponseCode();
            if (!isRedirect(status)) {
                return connection;
            }
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (!StringUtils.hasText(location)) {
                throw new IllegalArgumentException("media url redirect missing location");
            }
            current = current.resolve(location.trim());
            String scheme = current.getScheme() == null ? "" : current.getScheme().toLowerCase(Locale.ROOT);
            if (!allowedSchemes.contains(scheme)) {
                throw new IllegalArgumentException("unsupported media redirect scheme: " + scheme);
            }
        }
        throw new IllegalArgumentException("media url redirect exceeds limit: " + MAX_REDIRECTS);
    }

    /**
     * 判断 HTTP 状态码是否为跳转。
     *
     * @param status HTTP 状态码。
     * @return true 表示需要读取 Location 并继续访问。
     */
    private boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER
                || status == 307
                || status == 308;
    }

    /**
     * 判断 MIME 是否适合直接作为文本上下文拼入模型输入。
     *
     * @param mimeType 已解析 MIME。
     * @return 文本类返回 true。
     */
    private boolean isTextLike(MimeType mimeType) {
        String normalized = mimeType.toString().toLowerCase(Locale.ROOT);
        return "text".equals(mimeType.getType()) || "application/json".equals(normalized);
    }

    /**
     * 为模型资源生成可读文件名，帮助部分 Provider 判断文件类型。
     *
     * @param uri      媒体 URL。
     * @param mimeType 媒体 MIME 类型。
     * @return 文件名。
     */
    private String safeFilename(URI uri, MimeType mimeType) {
        String path = uri.getPath();
        String filename = StringUtils.getFilename(path);
        if (StringUtils.hasText(filename) && filename.contains(".")) {
            return filename;
        }
        String subtype = mimeType.getSubtype().replace("+", "-").replaceAll("[^a-zA-Z0-9-]", "");
        return "attachment." + subtype;
    }

    /**
     * 字符串必填校验。
     *
     * @param value     待校验内容。
     * @param fieldName 字段名，用于拼装错误提示。
     * @return 去除首尾空白后的内容。
     */
    private String requiredText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) { // 检查 null、空串和纯空白。
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim(); // 返回去除首尾空白后的内容。
    }

    /**
     * 校验通过的单个媒体资源。
     *
     * @param mimeType      媒体 MIME 类型。
     * @param resource      媒体资源，交给 Spring AI 组装多模态消息；文本类 URL 为空。
     * @param sourceSummary 审计摘要中的安全来源描述。
     * @param sizeBytes     保留字段；远程 URL 媒体为 0。
     * @param textContent   文本类 URL 内容，非文本媒体为空。
     */
    public record ValidatedMedia(MimeType mimeType, Resource resource, String sourceSummary, long sizeBytes, String textContent) {
    }

    /**
     * 带文件名和大小的字节资源。
     */
    private static class SizedByteArrayResource extends ByteArrayResource {

        private final String filename; // 传给模型 SDK 的资源文件名。
        private final long sizeBytes; // 资源字节大小。

        SizedByteArrayResource(byte[] byteArray, String filename) {
            super(byteArray);
            this.filename = filename;
            this.sizeBytes = byteArray.length;
        }

        @Override
        public String getFilename() {
            return filename;
        }

        long sizeBytes() {
            return sizeBytes;
        }
    }
}
