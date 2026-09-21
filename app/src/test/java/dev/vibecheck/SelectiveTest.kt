package dev.vibecheck

import org.junit.Assert.*
import org.junit.Test

class SelectiveTest {

    private fun dist(top: String, p: Double, other: String = "在表达不满") =
        Jev.Answer.Dist(top, mapOf(top to p, other to 1 - p))

    @Test fun bantersIsRoutineAndGetsOneLine() {
        // What most turns look like now: a joke, low risk, no rush. This must NOT dump five blocks.
        val a = mapOf(
            "intent" to dist("在开玩笑或一起感慨", 0.78),
            "danger" to Jev.Answer.Scored(0.6, 6),
            "urgency" to Jev.Answer.Noul(0.3),
            "need" to dist("接梗一起玩", 0.95),
            "action" to dist("接梗顺着聊", 0.87),
        )
        assertTrue(Jev.isRoutine(a))
        val card = Jev.routineCard(a)
        assertEquals(1, card.size)
        assertEquals(1, card[0].lines.size)
        assertTrue(card[0].lines[0].contains("开玩笑"))
    }

    @Test fun aRealSituationIsNotRoutine() {
        // "今晚看到这了" read as wanting to end the conversation: this is the turn that matters.
        val a = mapOf(
            "intent" to dist("明确想结束话题或拉开距离", 0.74),
            "danger" to Jev.Answer.Scored(1.5, 6),
            "urgency" to Jev.Answer.Noul(0.25),
        )
        assertFalse(Jev.isRoutine(a))
        assertTrue("the full card still renders", Jev.card(a).isNotEmpty())
    }

    @Test fun riskAloneEscalatesEvenIfIntentLooksCasual() {
        val a = mapOf(
            "intent" to dist("在开玩笑或一起感慨", 0.8),
            "danger" to Jev.Answer.Scored(3.0, 6),     // model smells trouble under the joke
            "urgency" to Jev.Answer.Noul(0.2),
        )
        assertFalse(Jev.isRoutine(a))
    }

    @Test fun urgencyEscalates() {
        val a = mapOf(
            "intent" to dist("单纯想知道答案", 0.9),
            "danger" to Jev.Answer.Scored(0.5, 6),
            "urgency" to Jev.Answer.Noul(0.8),         // they are waiting
        )
        assertFalse(Jev.isRoutine(a))
    }

    @Test fun aWeakCasualReadIsNotTrustedAsRoutine() {
        val a = mapOf(
            "intent" to dist("在开玩笑或一起感慨", 0.45),   // could be anything
            "danger" to Jev.Answer.Scored(0.5, 6),
            "urgency" to Jev.Answer.Noul(0.2),
        )
        assertFalse(Jev.isRoutine(a))
    }

    @Test fun routineCardSurfacesANonObviousAction() {
        val a = mapOf(
            "intent" to dist("在分享观点或心情", 0.7),
            "danger" to Jev.Answer.Scored(0.4, 6),
            "urgency" to Jev.Answer.Noul(0.2),
            "action" to dist("先回应情绪", 0.6),        // not the default for a share
        )
        assertTrue(Jev.routineCard(a)[0].lines[0].contains("先回应情绪"))
    }
}

class FingerprintMatchTest {

    @Test fun aWobbleIsTheSamePerson() {
        // Same avatar, two scans: a couple of bits differ from anti-aliasing.
        assertEquals(0, Person.hamming("#a5f1", "#a5f1"))
        assertEquals(1, Person.hamming("#a5f1", "#a5f0"))
        assertEquals(2, Person.hamming("#a5f1", "#a5f2"))
        assertTrue("a different avatar flips many bits", Person.hamming("#a5f1", "#5a0e") > 8)
    }

    @Test fun garbageNeverMatches() {
        assertEquals(Int.MAX_VALUE, Person.hamming("#zz", "#a5f1"))
        assertEquals(Int.MAX_VALUE, Person.hamming("Mia", "#a5f1"))
        assertTrue(Person.isFingerprint("#a5f1"))
        assertFalse(Person.isFingerprint("Mia"))
    }

    @Test fun blockMeansAreStableWhereSinglePixelsAreNot() {
        // Two samplings of the same avatar, differing by rendering noise of a few grey levels.
        val a = intArrayOf(200, 40, 180, 60, 90, 210, 30, 150, 120, 70, 190, 50, 160, 80, 220, 100)
        val b = a.map { it + (if (it % 3 == 0) 1 else -1) }.toIntArray()
        val ha = Person.hashOf(a)
        val hb = Person.hashOf(b)
        assertTrue("a few grey levels of noise must not change the identity", Person.hamming(ha, hb) <= 3)
    }
}
