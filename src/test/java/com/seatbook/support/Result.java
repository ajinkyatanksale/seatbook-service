package com.seatbook.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Outcome of one HTTP call. status -1 means the call itself failed (timeout, connection reset). */
public record Result(int status, String body) {

    public String error()         { return field("error"); }
    public String reservationId() { return field("reservation_id"); }
    public String userId()        { return field("user_id"); }

    /** Anything that is not a clean HTTP answer below 500 counts as a server-side failure. */
    public boolean serverError()  { return status < 100 || status >= 500; }

    /** Extracts a top-level-looking string field ("name":"value") without needing a JSON library. */
    public String field(String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(body == null ? "" : body);
        return m.find() ? m.group(1) : null;
    }
}
