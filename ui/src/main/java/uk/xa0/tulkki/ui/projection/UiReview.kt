package uk.xa0.tulkki.ui.projection

import uk.xa0.tulkki.translation.Review
import uk.xa0.tulkki.translation.ReviewMarks

/**
 * The review card's content for one own row, "Design: the Compose UI" §2.2's `review` field, defined
 * by §2.2.1 "The missing definitions" #3 as **reuse**: `UiReview(notes, marks)`.
 *
 * <p>`notes` are [Review.Note] - `remark()` plus the flagged words - filled from `Review.items()`;
 * `marks` are [ReviewMarks.Mark] - `start()`/`end()`/`slice()`/`remark()` - filled by
 * `ReviewMarks.in(review, text)`. Both are `:translation`'s own types, for the reason `ui-2` part 1
 * reused `DisplayedBody.Cover`: §2.1 already routes `ReviewKey`/`ReviewMarks` from `:translation`
 * into the projector, and a second note or mark vocabulary in `:ui` would be a second answer to the
 * same question.
 *
 * <p>Two consequences the projector owns and this type only records: the offsets are into the string
 * the review was keyed on - i.e. `top`'s own text - and notes pair with marks only by
 * `Mark.remark()`, a note whose slice misses or overlaps getting no mark. `UiReview` is non-null only
 * where `ReviewKey.forBubble` returns a key (own rows, interpreter on, a non-blank body,
 * `TRANSLATION_DONE`); the nullability is the design's, not this type's.
 */
data class UiReview(
    val notes: List<Review.Note>,
    val marks: List<ReviewMarks.Mark>,
)
