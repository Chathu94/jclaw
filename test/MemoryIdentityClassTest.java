import memory.MemoryIdentityClass;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * JCLAW-529: which captured memories reach the always-loaded core tier. The gate is
 * deterministic because admission invalidates the prompt-cache prefix, so a model's
 * opinion is not a safe basis for it — see the class note.
 */
class MemoryIdentityClassTest extends UnitTest {

    @Test
    void matchesKinshipFacts() {
        assertTrue(MemoryIdentityClass.isIdentity("The user's son Kheshav was born on Feb 7, 2008."));
        assertTrue(MemoryIdentityClass.isIdentity("The user's wife is named Renu."));
        assertTrue(MemoryIdentityClass.isIdentity("The user has two sons named Aaditya and Kheshav."));
        assertTrue(MemoryIdentityClass.isIdentity("The user's daughters go by Mimi and Taz."));
    }

    @Test
    void matchesSelfAndAttachmentFacts() {
        assertTrue(MemoryIdentityClass.isIdentity("The user's name is Tarun."));
        assertTrue(MemoryIdentityClass.isIdentity("The user lives in Kuala Lumpur."));
        assertTrue(MemoryIdentityClass.isIdentity("The user works at Abundent."));
        assertTrue(MemoryIdentityClass.isIdentity("The user's timezone is Asia/Kuala_Lumpur."));
    }

    @Test
    void doesNotMatchOrdinaryFacts() {
        assertFalse(MemoryIdentityClass.isIdentity("The deploy pipeline requires manual approval."));
        assertFalse(MemoryIdentityClass.isIdentity("The user prefers dark mode in the editor."));
        assertFalse(MemoryIdentityClass.isIdentity("The user is a staff engineer."));
    }

    @Test
    void doesNotMatchAFactThatMerelyNamesAPerson() {
        // The exact shape that started this: the relation is absent, so the memory reads as
        // a fact about two names rather than about the user's children. It is not identity
        // class until capture writes the relation into it, which is the point of the
        // propositional extraction change — the two fixes are coupled.
        assertFalse(MemoryIdentityClass.isIdentity(
                "The user said that Kheshav's nickname is Lyuvez and Aaditya's nickname is Dudez."));
    }

    @Test
    void failsClosedOnNullBlankAndNonThirdPerson() {
        assertFalse(MemoryIdentityClass.isIdentity(null));
        assertFalse(MemoryIdentityClass.isIdentity("   "));
        // EXTRACTION_INSTRUCTIONS mandates the third person; anything else does not qualify,
        // which for a capped always-loaded tier is the safe direction to be wrong in.
        assertFalse(MemoryIdentityClass.isIdentity("My son Arun goes by Bo."));
    }
}
