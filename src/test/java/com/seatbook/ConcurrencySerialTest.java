package com.seatbook;

import org.springframework.boot.test.context.SpringBootTest;

/** Same suite with a single booking permit: slow but must be exactly as correct. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.booking.max-concurrent=1")
class ConcurrencySerialTest extends ConcurrencyTest {
}
