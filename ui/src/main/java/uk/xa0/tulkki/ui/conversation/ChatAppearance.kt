package uk.xa0.tulkki.ui.conversation

import uk.xa0.tulkki.data.AppSettings

/**
 * The drawing switches a conversation's rows are **shown with**, so a changed one re-shows them.
 *
 * <p>**Why it is a value and not four arguments passed twice.** The live list is shown once per view and
 * re-shown when its switches move - the same rule [uk.xa0.tulkki.ui.projection.ProjectionSettings] keeps
 * for the content switches - and a caller that compared only the conversation and the content switches
 * would leave a list drawn with the old avatar setting until the owner left the conversation and came
 * back. Two values compared in one place is what makes "a changed switch re-shows the rows" true for the
 * appearance as well as for the content.
 *
 * <p>**It is not the projector's.** [uk.xa0.tulkki.ui.projection.ProjectionSettings] decides what a row
 * *says*; this decides how much of it is drawn, and the projector is deliberately not allowed to read it:
 * an avatar's presence is geometry, not content, and a concealment decision that consulted it would be a
 * second path.
 *
 * @param avatarsOn the owner's `show_avatars`: off is `AvatarPlacement.GONE` and no reserved column
 *     either, on reserves the run's column and draws the avatar where the run's edge is
 * @param colorful the owner's `use_green_background`, the tree's `colorfulChatBubbles`, which picks the
 *     bubble's tone family
 * @param formattingMarks the owner's `show_formatting_marks`: off draws a completed run as its content
 *     (`*bold*` is bold `bold`), on keeps the marker glyphs and draws them dimmed beside that content
 */
data class ChatAppearance(
    val avatarsOn: Boolean,
    val colorful: Boolean,
    val formattingMarks: Boolean,
) {

    companion object {

        /**
         * The tree's own defaults, for a caller that has not read the owner's settings: all three of
         * `AppSettings`' resources are off - `data/src/main/res/values/defaults.xml` carries
         * `show_avatars=false`, `use_green_background=false` and `show_formatting_marks=false` - so a
         * fresh install reserves no avatar column, draws the plain `SURFACE` family and consumes the
         * markers.
         *
         * <p>**It is not `ConversationScreen`'s own parameter default**, and the difference is the
         * point of this holder: the screen defaults `colorful = true` for a *caller with no settings at
         * all* (a preview, a JVM cell, which is what the screenshot fixture is), while a live
         * conversation has the owner's answer and must pass it. The live host passed nothing, so it drew
         * the colourful family for an owner whose setting was off.
         */
        val SHIPPED = ChatAppearance(avatarsOn = false, colorful = false, formattingMarks = false)

        /** The owner's answers, read once per show by the host that has the settings. */
        @JvmStatic
        fun of(settings: AppSettings): ChatAppearance =
            ChatAppearance(
                avatarsOn = settings.isShowAvatars(),
                colorful = settings.isColorfulChatBubbles(),
                formattingMarks = settings.isShowFormattingMarks(),
            )
    }
}
