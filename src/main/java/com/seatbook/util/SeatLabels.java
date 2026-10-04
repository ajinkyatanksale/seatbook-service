package com.seatbook.util;

import com.seatbook.error.DomainException;
import com.seatbook.error.ErrorCode;
import java.util.*;


public final class SeatLabels {

    private SeatLabels() {}

    public static List<String> getSortedLabels(List<String> seatList) {
        if (seatList == null || seatList.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "seats must not be empty");
        }
        List<String> out = new ArrayList<>(seatList.size());
        for (String s : seatList) {
            if (s == null || s.isBlank()) {
                throw new DomainException(ErrorCode.VALIDATION_ERROR, "seat labels must not be blank");
            }
            out.add(s.trim());
        }
        if (new HashSet<>(out).size() != out.size()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "duplicate seat labels");
        }
        Collections.sort(out);
        return out;
    }
}
