package com.hermes.agent.ui.theme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Rounding matches the Nous design system: chips/inputs ~11dp, cards 13–15dp,
// hero cards/sheets 18dp. `extraLarge` is what Material3's Button/OutlinedButton/
// TextButton/FilledTonalButton/ElevatedButton default to when a call site doesn't
// pass its own `shape =` — nothing in the app reads this token directly. It used
// to be 24dp, which reads as a full pill on standard button heights; kept equal
// to `medium` now so every button is square-with-rounded-corners regardless of
// whether it relies on this default or explicitly passes shapes.medium.
val HermesShapes = Shapes(
    extraSmall = RoundedCornerShape(7.dp),
    small = RoundedCornerShape(11.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(14.dp),
)

/** Chat bubble corner radius — asymmetric for a "tail" feel. */
val ChatBubbleRadius = 16.dp
