package com.seatbook;

import org.springframework.boot.test.context.SpringBootTest;

/** Same suite with more permits than pool connections: Hikari becomes the queue, correctness must not change. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.booking.max-concurrent=64")
class ConcurrencyWideTest extends ConcurrencyTest {
}
