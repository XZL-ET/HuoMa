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
 * 使用 ZXing 生成二维码，支持 300dpi（打印）和 72dpi（线上），叠加学校名称和自定义样式。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QrImageService {

    private final QrCodeRepository qrCodeRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Map<String, Color> THEME_COLORS = Map.of(
        "blue",   new Color(0x0d, 0x6e, 0xfd),
        "green",  new Color(0x19, 0x87, 0x54),
        "orange", new Color(0xfd, 0x7e, 0x14),
        "purple", new Color(0x6f, 0x42, 0xc1),
        "red",    new Color(0xdc, 0x35, 0x45),
        "gray",   new Color(0x6c, 0x75, 0x7d)
    );

    private static final Color DEFAULT_COLOR = new Color(0x0d, 0x6e, 0xfd);

    /**
     * 生成二维码图片。
     * @param qrCodeId 活码 ID
     * @param dpi 分辨率（72 或 300）
     * @return PNG 格式图片字节数组
     */
    public byte[] generateQrImage(Long qrCodeId, int dpi) {
        QrCode qr = qrCodeRepo.findById(qrCodeId)
            .orElseThrow(() -> new RuntimeException("活码不存在: " + qrCodeId));

        int qrSize = dpi == 300 ? 1024 : 256;
        int margin = dpi == 300 ? 80 : 20;
        int fontSize = dpi == 300 ? 48 : 14;

        try {
            // 生成基础二维码
            BufferedImage qrImage = generateBaseQr(qr.getQrUrl(), qrSize);

            // 读取样式配置
            StyleConfig style = parseStyleConfig(qr.getStyleConfig());

            // 计算画布尺寸
            int textHeight = 0;
            String guideText = style.guideText;
            if (guideText != null && !guideText.isEmpty()) {
                String[] lines = guideText.split("\\\\n");
                textHeight = lines.length * (fontSize + 4);
            } else if (style.showSchoolName) {
                textHeight = fontSize + 4;
            }

            int canvasWidth = qrSize + margin * 2;
            int canvasHeight = qrSize + margin * 2 + textHeight;

            BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = canvas.createGraphics();

            // 白色背景
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, canvasWidth, canvasHeight);

            // 绘制二维码
            g.drawImage(qrImage, margin, margin, null);

            // 叠加 Logo
            if (style.logoPath != null) {
                try {
                    Path logoFile = Paths.get(style.logoPath);
                    if (Files.exists(logoFile)) {
                        BufferedImage logo = ImageIO.read(logoFile.toFile());
                        int logoSize = qrSize / 5;
                        int logoX = (canvasWidth - logoSize) / 2;
                        int logoY = (margin + qrSize / 2) - logoSize / 2;
                        g.drawImage(logo.getScaledInstance(logoSize, logoSize, Image.SCALE_SMOOTH),
                            logoX, logoY, null);
                    }
                } catch (Exception e) {
                    log.debug("Logo 加载失败: {}", style.logoPath);
                }
            }

            // 绘制底部文字
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.BLACK);
            Font font = new Font("Microsoft YaHei", Font.PLAIN, fontSize);
            g.setFont(font);

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
                    int x = (canvasWidth - textWidth) / 2;
                    g.drawString(line, x, y);
                    y += fontSize + 4;
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
     */
    private BufferedImage generateBaseQr(String url, int size) throws WriterException {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H); // 高容错，可叠 Logo
        hints.put(EncodeHintType.MARGIN, 1);

        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix bitMatrix = writer.encode(url != null ? url : "about:blank",
            BarcodeFormat.QR_CODE, size, size, hints);

        MatrixToImageConfig config = new MatrixToImageConfig(0xFF000000, 0xFFFFFFFF);
        return MatrixToImageWriter.toBufferedImage(bitMatrix, config);
    }

    private StyleConfig parseStyleConfig(String json) {
        StyleConfig config = new StyleConfig();
        if (json == null || json.isEmpty()) return config;
        try {
            JsonNode node = objectMapper.readTree(json);
            config.logoPath = node.has("logo_path") && !node.get("logo_path").isNull()
                ? node.get("logo_path").asText() : null;
            config.theme = node.has("theme") ? node.get("theme").asText() : "blue";
            config.guideText = node.has("guide_text") ? node.get("guide_text").asText() : null;
            config.showSchoolName = node.has("show_school_name")
                && node.get("show_school_name").asBoolean();
        } catch (Exception ignored) {}
        return config;
    }

    private static class StyleConfig {
        String logoPath;
        String theme = "blue";
        String guideText;
        boolean showSchoolName = true;
    }
}
