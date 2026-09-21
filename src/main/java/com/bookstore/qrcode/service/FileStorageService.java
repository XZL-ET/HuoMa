package com.bookstore.qrcode.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 图片存储统一入口：封面图 / 表单卡片图。
 * <p>
 * 配置了阿里云 OSS（endpoint + accessKey + bucket 均非空）时，上传返回 OSS 公网绝对 URL，
 * 删除按对象存储清理；否则回退本地磁盘（返回相对路径 {@code /uploads/...}），
 * 保证本地开发与测试环境无需 OSS 凭据即可运行。
 * </p>
 */
@Slf4j
@Service
public class FileStorageService {

    private static final Set<String> ALLOWED_EXT = Set.of("png", "jpg", "jpeg", "gif", "webp");

    @Value("${oss.endpoint:}")
    private String ossEndpoint;
    @Value("${oss.access-key-id:}")
    private String ossAccessKeyId;
    @Value("${oss.access-key-secret:}")
    private String ossAccessKeySecret;
    @Value("${oss.bucket:}")
    private String ossBucket;
    @Value("${oss.public-base-url:}")
    private String ossPublicBaseUrl;

    @Value("${upload.card-pic-dir:./data/uploads/card-pics}")
    private String cardPicDir;
    @Value("${upload.grade-cover-dir:./data/uploads/grade-covers}")
    private String gradeCoverDir;

    public boolean ossEnabled() {
        return hasText(ossEndpoint) && hasText(ossAccessKeyId)
            && hasText(ossAccessKeySecret) && hasText(ossBucket);
    }

    /**
     * 存储图片，返回访问 URL（OSS 绝对 URL 或本地相对路径）。空文件返回 null。
     *
     * @param dirKey 逻辑目录：{@code "card-pics"} 或 {@code "grade-covers"}
     */
    public String store(MultipartFile file, String dirKey) {
        if (file == null || file.isEmpty()) return null;
        try {
            String ext = extractExt(file.getOriginalFilename());
            if (!ALLOWED_EXT.contains(ext)) {
                throw new RuntimeException("不支持的图片格式: " + ext);
            }
            String filename = UUID.randomUUID().toString().substring(0, 8) + "." + ext;
            return ossEnabled() ? storeToOss(file, dirKey, filename)
                                : storeToLocal(file, dirKey, filename);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("图片上传失败", e);
            throw new RuntimeException("图片上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除图片。URL 以 {@code http} 开头按 OSS 对象删，否则按本地文件删；失败仅告警不抛。
     */
    public void delete(String url) {
        if (!hasText(url)) return;
        try {
            if (url.startsWith("http")) {
                deleteFromOss(url);
            } else {
                deleteFromLocal(url);
            }
        } catch (Exception e) {
            log.warn("删除图片文件失败: {}", url, e);
        }
    }

    // ==================== OSS ====================

    private String publicBaseUrl() {
        if (hasText(ossPublicBaseUrl)) return ossPublicBaseUrl.replaceAll("/+$", "");
        return "https://" + ossBucket + "." + ossEndpoint;
    }

    private String storeToOss(MultipartFile file, String dirKey, String filename) throws Exception {
        String objectKey = dirKey + "/" + filename;
        OSS oss = buildClient();
        try (InputStream in = file.getInputStream()) {
            oss.putObject(ossBucket, objectKey, in);
        } finally {
            oss.shutdown();
        }
        String url = publicBaseUrl() + "/" + objectKey;
        log.info("图片已上传 OSS: {}", url);
        return url;
    }

    private void deleteFromOss(String url) throws Exception {
        String objectKey = new URI(url).getPath().replaceFirst("^/", "");
        OSS oss = buildClient();
        try {
            oss.deleteObject(ossBucket, objectKey);
        } finally {
            oss.shutdown();
        }
    }

    private OSS buildClient() {
        return new OSSClientBuilder().build(ossEndpoint, ossAccessKeyId, ossAccessKeySecret);
    }

    // ==================== 本地 fallback ====================

    private String localDir(String dirKey) {
        return "card-pics".equals(dirKey) ? cardPicDir : gradeCoverDir;
    }

    private String storeToLocal(MultipartFile file, String dirKey, String filename) throws Exception {
        Path dir = Path.of(localDir(dirKey)).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Path target = dir.resolve(filename);
        file.transferTo(target.toFile());
        log.info("图片已保存本地: {}", target);
        return "/uploads/" + dirKey + "/" + filename;
    }

    private void deleteFromLocal(String url) throws Exception {
        String filename = url.substring(url.lastIndexOf('/') + 1);
        String dirKey = url.contains("/card-pics/") ? "card-pics" : "grade-covers";
        Path dir = Path.of(localDir(dirKey)).toAbsolutePath().normalize();
        Files.deleteIfExists(dir.resolve(filename));
    }

    // ==================== 工具 ====================

    private String extractExt(String originalName) {
        if (originalName == null || !originalName.contains(".")) return "";
        return originalName.substring(originalName.lastIndexOf('.') + 1)
            .toLowerCase(Locale.ROOT);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
