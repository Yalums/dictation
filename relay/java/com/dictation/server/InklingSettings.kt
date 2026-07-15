package com.dictation.server

import androidx.compose.runtime.mutableStateOf

// Conversion settings. Process-scoped singleton of MutableStates so the UI
// stays in sync without intents or persistence.
object InklingSettings {
    // Display
    val drawRuling     = mutableStateOf(true)    // dashed underline beneath each row
    val syntheticBold  = mutableStateOf(false)   // FT_GlyphSlot_Embolden
    val contrastBoost  = mutableStateOf(0f)      // 0.0–2.0, gamma post-process

    // Output
    val embedTextLayer = mutableStateOf(false)   // libharu cannot embed ifontscloud CID fonts
    val embedBookmarks = mutableStateOf(true)
    val splitLandscape = mutableStateOf(false)
    val jpegQuality    = mutableStateOf(90)      // 70–100

    // Comic / Manga (text mode disabled at native layer; force comic)
    val comicMode      = mutableStateOf(true)
    val ditherAlgo     = mutableStateOf(0)       // 0=none, 1=bayer, 2=floyd-steinberg
    val comicResize    = mutableStateOf(false)
    val comicResizeW   = mutableStateOf(1404)    // default to device width
    val comicResizeH   = mutableStateOf(1872)    // default to device height
}
