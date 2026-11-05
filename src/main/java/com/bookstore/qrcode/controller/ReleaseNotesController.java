package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.service.ReleaseNotesService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.format.DateTimeFormatter;

@Controller
@RequiredArgsConstructor
public class ReleaseNotesController {

    private static final DateTimeFormatter BUILD_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ReleaseNotesService releaseNotesService;

    @GetMapping("/release-notes")
    public String page(@RequestParam(name = "all", defaultValue = "false") boolean all, Model model) {
        model.addAttribute("timeline", releaseNotesService.timeline(all));
        model.addAttribute("showAll", all);
        releaseNotesService.buildTime()
            .ifPresent(time -> model.addAttribute("runningVersion", time.format(BUILD_FORMAT)));
        return "release-notes";
    }
}
