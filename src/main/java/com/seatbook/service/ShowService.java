package com.seatbook.service;

import com.seatbook.dto.requests.CreateShowRequest;
import com.seatbook.dto.responses.SeatView;
import com.seatbook.dto.responses.ShowResponse;
import com.seatbook.error.DomainException;
import com.seatbook.model.Seat;
import com.seatbook.model.Show;
import com.seatbook.repository.SeatRepository;
import com.seatbook.repository.ShowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static com.seatbook.error.ErrorCode.SHOW_NOT_FOUND;
import static com.seatbook.error.ErrorCode.VALIDATION_ERROR;

@Service
public class ShowService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;

    public ShowService(ShowRepository showRepository, SeatRepository seatRepository) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
    }

    @Transactional(readOnly = true)
    public ShowResponse createShow(CreateShowRequest createShowRequest) {
        UUID newShowId = UUID.randomUUID();
        int limit = createShowRequest.perUserLimit() != null ? createShowRequest.perUserLimit() : 4;
        List<String> seatList = createShowRequest.seats();
        Set<String> uniqueLabels = seatList.stream().map(String::trim).collect(Collectors.toSet());
        if (uniqueLabels.size() != seatList.size()) {
            throw new DomainException(VALIDATION_ERROR, "Duplicate seats found");
        }
        Show show = new Show(newShowId, createShowRequest.name(), createShowRequest.pricePaise(), limit);
        showRepository.insert(show);
        seatRepository.insertAll(newShowId, createShowRequest.seats());
        return getShow(newShowId);
    }

    public ShowResponse getShow(UUID showId) {
        Show show = showRepository.findById(showId).orElseThrow(() -> new DomainException(SHOW_NOT_FOUND, "Show not found for provided show id"));
        List<Seat> seats = seatRepository.findByShowId(showId);
        Map<String, Long> countByStatus = seats.stream().collect(Collectors.groupingBy(Seat::status, Collectors.counting()));
        List<SeatView> seatViews = seats.stream().map(seat1 -> new SeatView(seat1.label(), seat1.status())).toList();
        return new ShowResponse(showId, show.name(), show.pricePaise(), show.perUserLimit(), seats.size(), countByStatus.get("available").intValue(), countByStatus.get("held").intValue(), countByStatus.get("confirmed").intValue(), seatViews);

    }
}
