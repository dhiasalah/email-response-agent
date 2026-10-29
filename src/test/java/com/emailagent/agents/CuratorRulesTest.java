package com.emailagent.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.emailagent.model.FieldType;
import org.junit.jupiter.api.Test;

/** The curator's deterministic rules: what counts as the owner's words, and what a valid answer is. */
class CuratorRulesTest {

    @Test
    void quotedOriginalsAreCutInEveryCommonFormat() {
        assertThat(CuratorAgent.ownerText("Room B204.\n\nOn Mon, 27 Oct 2026, Lina <l@s.example> wrote:\n> fake"))
                .isEqualTo("Room B204.");
        assertThat(CuratorAgent.ownerText("Salle B204.\n\nLe lun. 27 oct. 2026 à 10:05, Lina a écrit :\n> faux"))
                .isEqualTo("Salle B204.");
        assertThat(CuratorAgent.ownerText("Yes.\n-----Original Message-----\nFrom: x\nthe 1st of January"))
                .isEqualTo("Yes.");
        assertThat(CuratorAgent.ownerText("> quoted first\nMy answer: 3 June\n>> nested")).isEqualTo("My answer: 3 June");
        assertThat(CuratorAgent.ownerText("Bonjour,\nDe : Lina\nÀ : Prof\n> 1er janvier")).isEqualTo("Bonjour,");
    }

    @Test
    void aValueMustBeWrittenByTheOwner() {
        String own = "Les notes seront publiées le 18 juin 2027.";
        assertThat(CuratorAgent.writtenBy(own, "18 juin 2027")).isTrue();
        assertThat(CuratorAgent.writtenBy(own, "18 Juin, 2027")).isTrue(); // case and punctuation don't matter
        assertThat(CuratorAgent.writtenBy(own, "19 juin 2027")).isFalse();
        assertThat(CuratorAgent.writtenBy(own, "!!!")).isFalse(); // nothing left after folding
    }

    @Test
    void aLearnedValueMustHaveTheShapeOfAnAnswer() {
        assertThat(CuratorAgent.shape(FieldType.DATE, "published on 18 June 2027.")).isEqualTo("2027-06-18");
        assertThat(CuratorAgent.shape(FieldType.DATE, "le 1er juin 2027")).isEqualTo("2027-06-01");
        assertThat(CuratorAgent.shape(FieldType.DATE, "Je n'ai pas encore la date")).isNull();
        assertThat(CuratorAgent.shape(FieldType.DATE, "le 1er janvier")).isNull(); // no year: not a date we can use
        assertThat(CuratorAgent.shape(FieldType.YES_NO, "oui, en ligne")).isEqualTo("oui, en ligne");
        assertThat(CuratorAgent.shape(FieldType.YES_NO, "later this week")).isNull();
        assertThat(CuratorAgent.shape(FieldType.TEXT, "Room B204")).isEqualTo("Room B204");
    }

    @Test
    void answersAreCheckedAgainstTheFieldType() {
        assertThat(CuratorAgent.validateAnswer(FieldType.DATE, " 18/06/2027 ")).isEqualTo("2027-06-18");
        assertThat(CuratorAgent.validateAnswer(FieldType.YES_NO, "Non")).isEqualTo("no");
        assertThat(CuratorAgent.validateAnswer(FieldType.TEXT, "Room​ B204\u0007")).isEqualTo("Room B204");
        assertThatThrownBy(() -> CuratorAgent.validateAnswer(FieldType.YES_NO, "maybe"))
                .hasMessage("the answer must be yes or no");
        assertThatThrownBy(() -> CuratorAgent.validateAnswer(FieldType.TEXT, "x".repeat(2001)))
                .hasMessageContaining("2000");
        assertThatThrownBy(() -> CuratorAgent.validateAnswer(FieldType.TEXT, " ​ ")).hasMessage("the answer is empty");
    }
}
