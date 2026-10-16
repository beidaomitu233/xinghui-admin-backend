package com.xinghuiTec.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporaryPasswordGeneratorTest {
    @Test
    void generatesIndependentPasswordsThatMeetTheManualEntryContract() {
        String first = TemporaryPasswordGenerator.generate();
        String second = TemporaryPasswordGenerator.generate();

        assertEquals(20, first.length());
        assertTrue(first.matches("[A-Za-z2-9!@$%]{20}"));
        assertNotEquals(first, second);
    }
}
