package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Base64

/**
 * Base64 that arrives without its padding.
 *
 * The basic decoder refuses it, and it refuses it with an IllegalArgumentException - an
 * unchecked exception, in the middle of decoding a key or a seed. A key that arrives without
 * its padding is a normal thing to find, so the padding is added; and base64 that is not
 * base64 at all becomes an IOException like every other bad input.
 */
class Base64PaddingTest {
    @Test fun `a missing padding is filled in`() {
        assertArrayEquals("hi".toByteArray(),decode64("aGk"))
        assertArrayEquals("hi".toByteArray(),decode64("aGk="))
        assertArrayEquals("abc".toByteArray(),decode64("YWJj"))
        assertArrayEquals("abcd".toByteArray(),decode64("YWJjZA"))
        assertArrayEquals("a".toByteArray(),decode64("YQ"))
    }

    @Test fun `the url safe alphabet is understood`() {
        assertArrayEquals(Base64.getDecoder().decode("+/8="),decode64("-_8"))
    }

    @Test fun `input that is not base64 is an IOException and not an unchecked exception`() {
        assertThrows(IOException::class.java) { decode64("a") }
        assertThrows(IOException::class.java) { decode64("!!!!") }
        assertThrows(IOException::class.java) { decode64("aGk=aGk") }
    }
}
