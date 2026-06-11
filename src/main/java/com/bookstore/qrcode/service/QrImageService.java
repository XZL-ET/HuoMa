package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 活码二维码图片生成服务。
 * <p>
 * 基于 ZXing 库生成二维码，核心流程：
 * <ol>
 *   <li>根据活码 URL 和尺寸生成基础黑白二维码（{@link #generateBaseQr}）</li>
 *   <li>解析 {@link QrCode#styleConfig} JSON 获取主题色、Logo 路径、引导文字等样式配置（{@link #parseStyleConfig}）</li>
 *   <li>在二维码中心叠加 Logo 图片</li>
 *   <li>在底部渲染引导文字或学校名称</li>
 *   <li>输出 PNG 字节数组</li>
 * </ol>
 * 支持双分辨率输出：72dpi 用于线上展示，300dpi 用于打印。
 * 主题色映射见 {@link #THEME_COLORS}，默认主题色为蓝色。
 * 内部使用 {@link StyleConfig} 承载解析后的配置项。
 * </p>
 *
 * @author Bookstore Dev
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QrImageService {

    private final QrCodeRepository qrCodeRepo;
    /** 用于解析样式配置 JSON 的 Jackson 对象映射器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 主题色映射表，将字符串标识映射为 {@link Color} 对象。预留字段，供后续渲染使用。 */
    private static final Map<String, Color> THEME_COLORS = Map.of(
        "blue",   new Color(0x0d, 0x6e, 0xfd),   // 蓝色（默认）
        "green",  new Color(0x19, 0x87, 0x54),   // 绿色
        "orange", new Color(0xfd, 0x7e, 0x14),   // 橙色
        "purple", new Color(0x6f, 0x42, 0xc1),   // 紫色
        "red",    new Color(0xdc, 0x35, 0x45),   // 红色
        "gray",   new Color(0x6c, 0x75, 0x7d)    // 灰色
    );

    /** 默认主题色（蓝色），用于未指定 theme 时的降级 */
    private static final Color DEFAULT_COLOR = new Color(0x0d, 0x6e, 0xfd);

    /**
     * 生成二维码图片。
     * <p>
     * 根据 dpi 决定输出尺寸和质量：
     * <ul>
     *   <li>300dpi（打印）：二维码 1024px，边距 80px，字号 48</li>
     *   <li>72dpi（线上）：二维码 256px，边距 20px，字号 14</li>
     * </ul>
     * 流程：生成基础二维码 → 解析样式配置 → 创建画布 → 叠加 Logo → 绘制引导文字/学校名 → 输出 PNG。
     * </p>
     *
     * @param qrCodeId 活码 ID
     * @param dpi 分辨率，仅支持 72（线上展示）或 300（打印输出）
     * @return PNG 格式图片字节数组
     * @throws RuntimeException 活码不存在、或二维码生成失败时抛出
     */
    public byte[] generateQrImage(Long qrCodeId, int dpi) {
        QrCode qr = qrCodeRepo.findById(qrCodeId)
            .orElseThrow(() -> new RuntimeException("活码不存在: " + qrCodeId));

        // 根据 dpi 选择对应的尺寸参数：打印用大尺寸高质量，线上用小尺寸
        int qrSize = dpi == 300 ? 1024 : 256;
        int margin = dpi == 300 ? 80 : 20;
        int fontSize = dpi == 300 ? 48 : 14;

        try {
            // 第一步：用 ZXing 生成基础黑白二维码矩阵
            BufferedImage qrImage = generateBaseQr(qr.getQrUrl(), qrSize);

            // 第二步：从活码配置 JSON 解析样式参数
            StyleConfig style = parseStyleConfig(qr.getStyleConfig());

            // 第三步：计算底部引导文字占据的高度（多行则累加）
            int textHeight = 0;
            String guideText = style.guideText;
            if (guideText != null && !guideText.isEmpty()) {
                String[] lines = guideText.split("\\\\n");
                textHeight = lines.length * (fontSize + 4);
            } else if (style.showSchoolName) {
                textHeight = fontSize + 4;
            }

            // 第四步：创建画布（二维码区域 + 边距 + 底部文字区域）
            int canvasWidth = qrSize + margin * 2;
            int canvasHeight = qrSize + margin * 2 + textHeight;

            BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = canvas.createGraphics();

            // 填充白色背景
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, canvasWidth, canvasHeight);

            // 在画布居中位置绘制二维码
            g.drawImage(qrImage, margin, margin, null);

            // 第五步：在二维码中心区域叠加 Logo 图片（缩放到二维码边长的 1/5）
            if (style.logoPath != null) {
                try {
                    Path logoFile = Paths.get(style.logoPath);
                    if (Files.exists(logoFile)) {
                        BufferedImage logo = ImageIO.read(logoFile.toFile());
                        int logoSize = qrSize / 5;
                        int logoX = (canvasWidth - logoSize) / 2;     // 水平居中
                        int logoY = (margin + qrSize / 2) - logoSize / 2; // 垂直居中于二维码区域
                        g.drawImage(logo.getScaledInstance(logoSize, logoSize, Image.SCALE_SMOOTH),
                            logoX, logoY, null);
                    }
                } catch (Exception e) {
                    log.debug("Logo 加载失败: {}", style.logoPath);
                }
            }

            // 第六步：在底部绘制引导文字或学校名称（居中显示）
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.BLACK);
            Font font = new Font("Microsoft YaHei", Font.PLAIN, fontSize);
            g.setFont(font);

            // 确定显示文案：优先引导文字，其次学校名称，二者都没有则不渲染
            String text;
            if (guideText != null && !guideText.isEmpty()) {
                text = guideText;
            } else if (style.showSchoolName) {
                text = qr.getSchoolName();
            } else {
                text = null;
            }

            if (text != null) {
                String[] lines = text.split("\\\\n");
                int y = qrSize + margin + fontSize;
                for (String line : lines) {
                    FontMetrics fm = g.getFontMetrics();
                    int textWidth = fm.stringWidth(line);
                    int x = (canvasWidth - textWidth) / 2; // 水平居中
                    g.drawString(line, x, y);
                    y += fontSize + 4; // 行间距 4px
                }
            }

            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(canvas, "PNG", baos);
            return baos.toByteArray();

        } catch (Exception e) {
            log.error("生成二维码图片失败: qrCodeId={}", qrCodeId, e);
            throw new RuntimeException("生成二维码失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成基础二维码（纯黑白方块）。
     * <p>
     * 使用 ZXing 的 {@link QRCodeWriter} 编码 URL 为 {@link BitMatrix}，
     * 纠错等级设为 {@link ErrorCorrectionLevel#H}（最高容错 ≈ 30% 恢复能力，允许叠加 Logo 遮挡部分区域），
     * 边距设为 1 模块。
     * 若 URL 为空则编码为 {@code about:blank} 占位，避免 ZXing 传入空值异常。
     * </p>
     *
     * @param url  二维码内容 URL
     * @param size 二维码边长（像素）
     * @return 黑白二维码 {@link BufferedImage}，黑色前景 / 白色背景
     * @throws WriterException ZXing 编码异常（如 URL 无效导致无法生成矩阵）
     */
    private BufferedImage generateBaseQr(String url, int size) throws WriterException {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H); // 最高容错，允许 Logo 遮挡约 30% 面积
        hints.put(EncodeHintType.MARGIN, 1); // 二维码白边 = 1 模块宽

        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix bitMatrix = writer.encode(url != null ? url : "about:blank",
            BarcodeFormat.QR_CODE, size, size, hints);

        // 黑色前景(0xFF000000) 白色背景(0xFFFFFFFF)
        MatrixToImageConfig config = new MatrixToImageConfig(0xFF000000, 0xFFFFFFFF);
        return MatrixToImageWriter.toBufferedImage(bitMatrix, config);
    }

    /**
     * 解析活码样式配置 JSON 字符串为 {@link StyleConfig} 对象。
     * <p>
     * 支持字段：
     * <ul>
     *   <li>{@code logo_path} — Logo 图片文件路径（绝对路径或相对于运行目录）</li>
     *   <li>{@code theme} — 主题色标识，映射见 {@link #THEME_COLORS}，默认为 {@code blue}</li>
     *   <li>{@code guide_text} — 引导文字，支持 {@code \n} 转义换行</li>
     *   <li>{@code show_school_name} — 是否在二维码底部显示学校名称</li>
     * </ul>
     * 若 JSON 为 {@code null} 或空字符串，或解析异常，均返回默认配置 {@link StyleConfig}。
     * </p>
     *
     * @param json 样式配置 JSON 字符串（如 {@code {"theme":"blue","logo_path":"/path/to/logo.png"}}）
     * @return 解析后的 {@link StyleConfig} 对象（不会为 {@code null}）
     */
    private StyleConfig parseStyleConfig(String json) {
        StyleConfig config = new StyleConfig();
        // JSON 为空或非法时直接返回默认配置，避免下游 null 判断
        if (json == null || json.isEmpty()) return config;
        try {
            JsonNode node = objectMapper.readTree(json);
            config.logoPath = node.has("logo_path") && !node.get("logo_path").isNull()
                ? node.get("logo_path").asText() : null;
            config.theme = node.has("theme") ? node.get("theme").asText() : "blue";
            config.guideText = node.has("guide_text") ? node.get("guide_text").asText() : null;
            config.showSchoolName = node.has("show_school_name")
                && node.get("show_school_name").asBoolean();
        } catch (Exception ignored) {
            // 解析异常静默忽略，使用 StyleConfig 默认值
        }
        return config;
    }

    /**
     * 二维码样式配置内部类。
     * <p>
     * 存储从活码 {@code style_config} JSON 解析出的外观参数，
     * 包括 Logo 路径、主题色、引导文字和学校名显示开关。
     * 各字段均有默认值以兼容未配置的情况。
     * </p>
     */
    private static class StyleConfig {
        /** Logo 图片路径，{@code null} 表示不显示 */
        String logoPath;
        /** 主题色标识，与 {@link #THEME_COLORS} 的 key 对应 */
        String theme = "blue";
        /** 底部引导文字，支持 {@code \n} 换行；{@code null} 表示不显示 */
        String guideText;
        /** 是否在底部显示学校名称（当 guideText 为空时生效） */
        boolean showSchoolName = true;
    }
}
