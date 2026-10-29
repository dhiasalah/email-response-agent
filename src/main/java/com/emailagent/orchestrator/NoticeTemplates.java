package com.emailagent.orchestrator;

import com.emailagent.model.Language;
import com.emailagent.model.SenderScope;
import com.emailagent.model.SlotType;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The texts the system writes without a model (design doc §7.5, §7.7, §6 timeouts). Their variable parts are trusted:
 * items from a fixed list per slot type, the owner's name, a fact value and its cohort. Nothing from the email is
 * echoed, not even the question: triage's model wrote that after reading the sender's text.
 */
public final class NoticeTemplates {

    private NoticeTemplates() {}

    private record Item(String en, String fr) {}

    /**
     * What a sender may be asked, per slot type (design doc §7.5: "only items from an allowed list"). Never an
     * identifier or anything sensitive: who the sender is comes from the roster, not from what they write.
     * INDIVIDUAL_RESULT has none: those emails go to the owner.
     */
    private static final Map<SlotType, Item> ITEMS = new EnumMap<>(SlotType.class);

    static {
        ITEMS.put(SlotType.GRADES_PUBLICATION_DATE, new Item("which course or semester you mean",
                "de quel cours ou semestre il s'agit"));
        ITEMS.put(SlotType.EXAM_DATE, new Item("which exam you mean (course name and session)",
                "de quel examen il s'agit (nom du cours et session)"));
        ITEMS.put(SlotType.EXAM_ROOM, new Item("which exam you mean (course name and session)",
                "de quel examen il s'agit (nom du cours et session)"));
        ITEMS.put(SlotType.DEADLINE, new Item("which course and which assignment you mean",
                "de quel cours et de quel rendu il s'agit"));
        ITEMS.put(SlotType.PROCEDURE, new Item("which procedure you mean (for example a grade appeal or an absence)",
                "de quelle démarche il s'agit (par exemple une contestation de note ou une absence)"));
        ITEMS.put(SlotType.DOCUMENT_AVAILABILITY, new Item("which course and which document you mean",
                "de quel cours et de quel document il s'agit"));
        ITEMS.put(SlotType.MEETING_AVAILABILITY, new Item("the purpose of the meeting and the days that suit you",
                "l'objet du rendez-vous et les jours qui vous conviennent"));
        ITEMS.put(SlotType.INDIVIDUAL_DECISION, new Item("which course and what exactly you are asking for",
                "de quel cours il s'agit et ce que vous demandez exactement"));
    }

    /** One question grouping every missing item, or empty if a type has nothing the sender may be asked. */
    public static Optional<String> clarification(List<SlotType> missing, Language lang, String ownerName,
            boolean disclosure) {
        if (missing.isEmpty() || missing.stream().anyMatch(t -> !ITEMS.containsKey(t))) {
            return Optional.empty();
        }
        Set<String> items = new LinkedHashSet<>();
        missing.forEach(t -> items.add(lang == Language.FR ? ITEMS.get(t).fr() : ITEMS.get(t).en()));
        StringBuilder out = new StringBuilder(lang == Language.FR
                ? "Bonjour,\n\nPour répondre à votre message, pourriez-vous me préciser :\n"
                : "Hello,\n\nTo answer your email, could you please tell me:\n");
        items.forEach(i -> out.append("- ").append(i).append('\n'));
        out.append(lang == Language.FR ? "\nIl suffit de répondre à ce message." : "\nJust reply to this email.");
        return Optional.of(out + closing(lang, ownerName, disclosure));
    }

    public static String holding(Language lang, String ownerName, boolean disclosure) {
        return (lang == Language.FR
                ? "Bonjour,\n\nJ'ai bien reçu votre message. Je dois vérifier une information avant de vous répondre ;"
                        + " vous aurez une réponse dès que possible."
                : "Hello,\n\nI have received your email. I need to check one point before answering;"
                        + " you will get a reply as soon as possible.")
                + closing(lang, ownerName, disclosure);
    }

    /**
     * One announcement in both languages, for a whole cohort. {@code topic} is the card's key (normalized by code,
     * from triage's model): acceptable here only because the owner approves this exact text before it leaves.
     */
    public static String broadcastBody(SenderScope scope, String label, String topic, String valueEn, String valueFr,
            String ownerName) {
        String cohort = scope.program() + " " + scope.track() + " (" + scope.year() + ")";
        return "Bonjour à tous,\n\nPlusieurs d'entre vous m'ont posé la question ; voici la réponse pour " + cohort
                + ".\n" + label + " - " + topic + " : " + valueFr
                + "\n\n---\n\nHello everyone,\n\nSeveral of you asked, so here is the answer for " + cohort + ".\n"
                + label + " - " + topic + ": " + valueEn
                + "\n\nCordialement / Best regards,\n" + ownerName;
    }

    public static String broadcastSubject(String label, String topic) {
        return "Information: " + label + " - " + topic;
    }

    /** A card key reduced to plain words, short: the only model-derived text in any template. */
    public static String topic(String entityKey) {
        String plain = entityKey == null ? "" : entityKey.replaceAll("[^\\p{L}\\p{N} -]", " ").replaceAll("\\s+", " ")
                .strip();
        return plain.length() > 60 ? plain.substring(0, 60).strip() : plain;
    }

    private static String closing(Language lang, String ownerName, boolean disclosure) {
        String out = "\n\n" + (lang == Language.FR ? "Cordialement," : "Best regards,") + "\n" + ownerName;
        if (disclosure) {
            out += "\n\n" + (lang == Language.FR
                    ? "Ce message a été préparé par un assistant IA."
                    : "This message was prepared by an AI assistant.");
        }
        return out;
    }
}
