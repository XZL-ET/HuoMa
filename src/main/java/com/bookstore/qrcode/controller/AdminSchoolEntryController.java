package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.QrAccessLog;
import com.bookstore.qrcode.repository.QrAccessLogRepository;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.ByteArrayOutputStream;

@Controller
@RequestMapping("/admin/school-entry")
@RequiredArgsConstructor
public class AdminSchoolEntryController {

    private final QrAccessLogRepository logRepository;

    @GetMapping
    public String index(Model model) {
        long viewCount = logRepository.countByChannel(QrAccessLog.Channel.school);
        model.addAttribute("entryUrl", "/s");
        model.addAttribute("viewCount", viewCount);
        return "admin/school-entry";
    }

    /** 动态生成入口二维码 PNG */
    @GetMapping(value = "/qr-image", produces = MediaType.IMAGE_PNG_VALUE)
    @ResponseBody
    public byte[] qrImage() throws Exception {
        String baseUrl = "/s"; // In production, prepend the actual domain
        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix matrix = writer.encode(baseUrl, BarcodeFormat.QR_CODE, 300, 300);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", out);
        return out.toByteArray();
    }
}
