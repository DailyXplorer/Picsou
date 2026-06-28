package com.picsou.controller;

import com.picsou.dto.RevolutCsvNamingResponse;
import com.picsou.dto.UnnamedPocketResponse;
import com.picsou.service.RevolutPocketService;
import com.picsou.service.UserContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/revolut-pockets")
public class RevolutPocketController {

    private final RevolutPocketService revolutPocketService;
    private final UserContext userContext;

    public RevolutPocketController(RevolutPocketService revolutPocketService, UserContext userContext) {
        this.revolutPocketService = revolutPocketService;
        this.userContext = userContext;
    }

    /**
     * List all unnamed Revolut pocket sub-accounts for the current member,
     * with their recent inflow transfers for the naming popup.
     * <p>
     * {@code GET /api/revolut-pockets/unnamed}
     */
    @GetMapping("/unnamed")
    public List<UnnamedPocketResponse> listUnnamed() {
        return revolutPocketService.listUnnamed(userContext.currentMemberId());
    }

    /**
     * Upload a Revolut CSV export to pre-fill pocket names by reconciling
     * transfer amount + date. Returns suggestions — never auto-applied.
     * <p>
     * {@code POST /api/revolut-pockets/csv-naming}
     */
    @PostMapping(value = "/csv-naming", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RevolutCsvNamingResponse namePocketsFromCsv(
        @RequestParam("file") MultipartFile file
    ) throws IOException {
        return revolutPocketService.namePocketsFromCsv(
            file.getInputStream(), userContext.currentMemberId());
    }
}
