package com.seatbook.controller;

import com.seatbook.auth.AuthenticationUser;
import com.seatbook.dto.requests.CreateShowRequest;
import com.seatbook.dto.responses.ShowResponse;
import com.seatbook.error.DomainException;
import com.seatbook.error.ErrorCode;
import com.seatbook.service.ShowService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import com.seatbook.auth.AuthFilter;

@RestController
public class ShowController {
    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    @PostMapping("/shows")
    public ResponseEntity<ShowResponse> createShow(@RequestAttribute("authUser") AuthenticationUser user, @Valid @RequestBody CreateShowRequest createShowRequest) {
        if (user.isAdmin()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Admin only");
        }
        ShowResponse showResponse = showService.createShow(createShowRequest);
        return new ResponseEntity<>(showResponse, HttpStatus.CREATED);
    }

    @GetMapping("/shows/{id}")
    public ResponseEntity<ShowResponse> getShow(@Valid @PathVariable("id") UUID showId) {
        ShowResponse showResponse = showService.getShow(showId);
        return new ResponseEntity<>(showResponse, HttpStatus.OK);
    }
}
