package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

/**
 * A cancelled request is not a failed one.
 *
 * The work helper used to recognise "am I still the current request?" by a number that
 * cancelling did not move, so a cancelled request came back as a failure and the page it was
 * on replaced its content with an error. These are the two rules that fix it.
 */
class WorkTicketTest {
    @Test fun aCancelledJobIsNoLongerTheCurrentOne() {
        val ticket = WorkTicket()
        val first = ticket.next()
        assertTrue(ticket.isCurrent(first))
        ticket.cancel()
        assertFalse(ticket.isCurrent(first))
    }

    @Test fun aNewJobSupersedesTheOneBeforeIt() {
        val ticket = WorkTicket()
        val first = ticket.next()
        val second = ticket.next()
        assertFalse(ticket.isCurrent(first))
        assertTrue(ticket.isCurrent(second))
    }

    @Test fun theSequenceTheWorkHelperUsesLeavesOnlyTheLatestJobCurrent() {
        val ticket = WorkTicket()
        val first = ticket.next()
        // Starting a new job cancels the previous one first, and only then takes a number.
        ticket.cancel()
        val second = ticket.next()
        assertFalse(ticket.isCurrent(first))
        assertTrue(ticket.isCurrent(second))
    }

    @Test fun aTicketIsNeverHandedOutTwice() {
        val ticket = WorkTicket()
        val numbers = (1..100).map { ticket.next() }
        assertEquals(numbers.size, numbers.toSet().size)
    }
}
