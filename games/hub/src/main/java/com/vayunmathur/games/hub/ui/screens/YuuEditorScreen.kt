package com.vayunmathur.games.hub.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.hub.R
import com.vayunmathur.games.hub.data.entities.YUU_EYE_COLORS
import com.vayunmathur.games.hub.data.entities.YUU_HAIR_COLORS
import com.vayunmathur.games.hub.data.entities.YUU_MOUTH_COLORS
import com.vayunmathur.games.hub.data.entities.YUU_SKIN_TONES
import com.vayunmathur.games.hub.data.entities.YuuFaceConfig
import com.vayunmathur.games.hub.data.entities.encode
import com.vayunmathur.games.hub.ui.components.YuuPlaceholder
import com.vayunmathur.games.hub.ui.components.YuuPoseGallery
import com.vayunmathur.games.hub.ui.yuu.EDITOR_SURFACE_ID
import com.vayunmathur.games.hub.ui.yuu.YuuSurfaceIds
import com.vayunmathur.library.avatar.components.YuuAvatar3D
import com.vayunmathur.games.hub.ui.yuu.YuuCategory
import com.vayunmathur.games.hub.ui.yuu.YuuEditorActions
import com.vayunmathur.games.hub.ui.yuu.YuuEditorUiState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.R as UiR

/**
 * Wii-style Yuu creator with a fixed, non-scrolling layout: preview zone (with icon
 * steppers overlaid for the active category's numeric tweaks), a compact name+save
 * row, the category rail, then the option-picker zone which is the ONLY scrollable
 * area. Fully local draft state until [YuuEditorActions.onSave].
 */
@Composable
fun YuuEditorScreen(
    state: YuuEditorUiState,
    actions: YuuEditorActions,
    modifier: Modifier = Modifier,
) {
    val draft = remember(state.editingId, state.initialConfig) {
        YuuDraft.from(state.initialConfig)
    }
    var name by remember(state.editingId, state.initialName) {
        mutableStateOf(state.initialName)
    }
    var category by remember { mutableStateOf(YuuCategory.Face) }
    var nameError by remember { mutableStateOf(false) }

    AppScaffold(
        title = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; nameError = false },
                label = { Text(stringResource(UiR.string.name)) },
                singleLine = true,
                isError = nameError,
                supportingText = if (nameError) {
                    { Text(stringResource(R.string.yuu_name_required)) }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        onNavigateBack = actions::onDismiss,
        actions = {
            IconButton(
                onClick = {
                    if (name.isBlank()) {
                        nameError = true
                    } else {
                        actions.onSave(name, draft.toConfig())
                    }
                }
            ) { IconSave() }
        },
    ) { padding ->
        Column(
            modifier = modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Zone 1: 3D preview with overlay steppers for the active category,
            // plus the pose gallery. Compact: wider aspect + height cap so the
            // option zone below gets room.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(max = 180.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        YuuAvatar3D(
                            configJson = draft.toConfig().encode(),
                            poseId = draft.poseId,
                            surfaceId = EDITOR_SURFACE_ID,
                            modifier = Modifier.fillMaxWidth(0.6f).aspectRatio(PREVIEW_ASPECT).padding(6.dp),
                            fallback = {
                                YuuPlaceholder(
                                    name = name.ifBlank { "…" },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            },
                            // Drag-to-rotate lives inside YuuAvatar3D
                            // (enableDragRotate): the gesture overlay floats over
                            // the surface, under this stepper overlay, so the
                            // avatar stays where left and IconButtons keep working.
                            enableDragRotate = true,
                        )
                        // Stepper overlay shares PREVIEW_ASPECT with the avatar so
                        // the rows bottom-anchor over the surface on both the 3D
                        // and placeholder paths.
                        PreviewSteppers(
                            category = category,
                            draft = draft,
                            modifier = Modifier.fillMaxWidth().aspectRatio(PREVIEW_ASPECT),
                        )
                    }
                    SectionLabel(stringResource(R.string.yuu_pose))
                    YuuPoseGallery(
                        selectedPoseId = draft.poseId,
                        onSelect = { draft.poseId = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // Zone 2: category rail (never scrolls the screen).
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(YuuCategory.entries) { cat ->
                    FilterChip(
                        selected = category == cat,
                        onClick = { category = cat },
                        label = { Text(stringResource(cat.labelRes())) },
                    )
                }
            }
            // Zone 3: the ONLY scrollable area — the option picker.
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (category) {
                    YuuCategory.Face -> FacePanel(draft)
                    YuuCategory.Body -> BodyPanel()
                    YuuCategory.Hair -> HairPanel(draft)
                    YuuCategory.Eyes -> EyesPanel(draft)
                    YuuCategory.Brows -> BrowsPanel(draft)
                    YuuCategory.Nose -> NosePanel(draft)
                    YuuCategory.Mouth -> MouthPanel(draft)
                    YuuCategory.Extras -> ExtrasPanel(draft)
                }
            }
        }
    }
}

/** Preview aspect (w/h): avatar and stepper overlay share it — keep in sync. */
private const val PREVIEW_ASPECT = 2.0f

private fun YuuCategory.labelRes(): Int = when (this) {
    YuuCategory.Face -> R.string.yuu_cat_face
    YuuCategory.Body -> R.string.yuu_cat_body
    YuuCategory.Hair -> R.string.yuu_cat_hair
    YuuCategory.Eyes -> R.string.yuu_cat_eyes
    YuuCategory.Brows -> R.string.yuu_cat_brows
    YuuCategory.Nose -> R.string.yuu_cat_nose
    YuuCategory.Mouth -> R.string.yuu_cat_mouth
    YuuCategory.Extras -> R.string.yuu_cat_extras
}

/** Mutable draft mirroring [YuuFaceConfig]; Compose observes each field. */
private class YuuDraft private constructor(
    faceShape: Int,
    skinTone: Long,
    earSize: Float,
    bodyHeight: Float,
    bodyWeight: Float,
    hairStyle: Int,
    hairColor: Long,
    eyeStyle: Int,
    eyeColor: Long,
    eyeSize: Float,
    eyeSpacing: Float,
    eyeHeight: Float,
    eyeRotation: Float,
    browStyle: Int,
    browColor: Long,
    browHeight: Float,
    browRotation: Float,
    noseStyle: Int,
    noseHeight: Float,
    mouthStyle: Int,
    mouthColor: Long,
    mouthSize: Float,
    mouthHeight: Float,
    glassesStyle: Int,
    beardStyle: Int,
    moleStyle: Int,
    wrinkleStyle: Int,
    blushStyle: Int,
    poseId: String?,
) {
    var faceShape by mutableIntStateOf(faceShape)
    var skinTone by mutableLongStateOf(skinTone)
    var earSize by mutableFloatStateOf(earSize)
    var bodyHeight by mutableFloatStateOf(bodyHeight)
    var bodyWeight by mutableFloatStateOf(bodyWeight)
    var hairStyle by mutableIntStateOf(hairStyle)
    var hairColor by mutableLongStateOf(hairColor)
    var eyeStyle by mutableIntStateOf(eyeStyle)
    var eyeColor by mutableLongStateOf(eyeColor)
    var eyeSize by mutableFloatStateOf(eyeSize)
    var eyeSpacing by mutableFloatStateOf(eyeSpacing)
    var eyeHeight by mutableFloatStateOf(eyeHeight)
    var eyeRotation by mutableFloatStateOf(eyeRotation)
    var browStyle by mutableIntStateOf(browStyle)
    var browColor by mutableLongStateOf(browColor)
    var browHeight by mutableFloatStateOf(browHeight)
    var browRotation by mutableFloatStateOf(browRotation)
    var noseStyle by mutableIntStateOf(noseStyle)
    var noseHeight by mutableFloatStateOf(noseHeight)
    var mouthStyle by mutableIntStateOf(mouthStyle)
    var mouthColor by mutableLongStateOf(mouthColor)
    var mouthSize by mutableFloatStateOf(mouthSize)
    var mouthHeight by mutableFloatStateOf(mouthHeight)
    var glassesStyle by mutableIntStateOf(glassesStyle)
    var beardStyle by mutableIntStateOf(beardStyle)
    var moleStyle by mutableIntStateOf(moleStyle)
    var wrinkleStyle by mutableIntStateOf(wrinkleStyle)
    var blushStyle by mutableIntStateOf(blushStyle)
    var poseId by mutableStateOf(poseId)

    fun toConfig() = YuuFaceConfig(
        faceShape = faceShape, skinTone = skinTone, earSize = earSize,
        bodyHeight = bodyHeight, bodyWeight = bodyWeight,
        hairStyle = hairStyle, hairColor = hairColor,
        eyeStyle = eyeStyle, eyeColor = eyeColor, eyeSize = eyeSize,
        eyeSpacing = eyeSpacing, eyeHeight = eyeHeight, eyeRotation = eyeRotation,
        browStyle = browStyle, browColor = browColor, browHeight = browHeight,
        browRotation = browRotation,
        noseStyle = noseStyle, noseHeight = noseHeight,
        mouthStyle = mouthStyle, mouthColor = mouthColor,
        mouthSize = mouthSize, mouthHeight = mouthHeight,
        glassesStyle = glassesStyle, beardStyle = beardStyle,
        moleStyle = moleStyle, wrinkleStyle = wrinkleStyle, blushStyle = blushStyle,
        poseId = poseId,
    )

    companion object {
        fun from(c: YuuFaceConfig) = YuuDraft(
            c.faceShape, c.skinTone, c.earSize, c.bodyHeight, c.bodyWeight,
            c.hairStyle, c.hairColor,
            c.eyeStyle, c.eyeColor, c.eyeSize, c.eyeSpacing, c.eyeHeight, c.eyeRotation,
            c.browStyle, c.browColor, c.browHeight, c.browRotation,
            c.noseStyle, c.noseHeight, c.mouthStyle,
            c.mouthColor, c.mouthSize, c.mouthHeight, c.glassesStyle, c.beardStyle, c.moleStyle,
            c.wrinkleStyle, c.blushStyle, c.poseId,
        )
    }
}

/**
 * Tweak sliders overlaid on the preview, one per numeric tweak of the active
 * category. Sliders bind directly to the draft field (0..1), replacing the
 * old icon steppers — continuous control instead of ±0.1 taps.
 */
@Composable
private fun PreviewSteppers(
    category: YuuCategory,
    draft: YuuDraft,
    modifier: Modifier = Modifier,
) {
    val rows: List<TweakSpec> = when (category) {
        YuuCategory.Face -> listOf(
            TweakSpec(R.string.yuu_ear_size, { draft.earSize }, { draft.earSize = it }),
        )
        YuuCategory.Body -> listOf(
            TweakSpec(R.string.yuu_body_height, { draft.bodyHeight }, { draft.bodyHeight = it }),
            TweakSpec(R.string.yuu_body_weight, { draft.bodyWeight }, { draft.bodyWeight = it }),
        )
        YuuCategory.Hair -> emptyList()
        YuuCategory.Eyes -> listOf(
            TweakSpec(R.string.yuu_eye_size, { draft.eyeSize }, { draft.eyeSize = it }),
            TweakSpec(R.string.yuu_eye_spacing, { draft.eyeSpacing }, { draft.eyeSpacing = it }),
            TweakSpec(R.string.yuu_eye_height, { draft.eyeHeight }, { draft.eyeHeight = it }),
            TweakSpec(R.string.yuu_eye_rotation, { draft.eyeRotation }, { draft.eyeRotation = it }),
        )
        YuuCategory.Brows -> listOf(
            TweakSpec(R.string.yuu_brow_height, { draft.browHeight }, { draft.browHeight = it }),
            TweakSpec(R.string.yuu_brow_rotation, { draft.browRotation }, { draft.browRotation = it }),
        )
        YuuCategory.Nose -> listOf(
            TweakSpec(R.string.yuu_nose_height, { draft.noseHeight }, { draft.noseHeight = it }),
        )
        YuuCategory.Mouth -> listOf(
            TweakSpec(R.string.yuu_mouth_size, { draft.mouthSize }, { draft.mouthSize = it }),
            TweakSpec(R.string.yuu_mouth_height, { draft.mouthHeight }, { draft.mouthHeight = it }),
        )
        YuuCategory.Extras -> emptyList()
    }
    if (rows.isEmpty()) return
    Column(
        modifier = modifier.padding(4.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        rows.forEach { spec ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(spec.labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(72.dp),
                )
                Slider(
                    value = spec.get(),
                    onValueChange = { spec.set(it.coerceIn(0f, 1f)) },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private class TweakSpec(
    val labelRes: Int,
    val get: () -> Float,
    val set: (Float) -> Unit,
)

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun VariantGrid(
    count: Int,
    selected: Int,
    render: @Composable (Int, Boolean) -> Unit,
) {
    // Non-lazy fixed grid inside the scrollable option zone: lays out rows directly.
    val columns = 4
    val rows = (count + columns - 1) / columns
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(rows) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0 until columns) {
                    val i = row * columns + col
                    if (i < count) {
                        Box(Modifier.weight(1f)) { render(i, i == selected) }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun VariantCell(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(
        onClick = onClick,
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null,
        modifier = Modifier.aspectRatio(1f),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

/**
 * Live-3D variant cell: renders the full draft config with one field
 * overridden, keyed by a stable cell id. Over the live-surface cap it shows
 * the placeholder (see [YuuSurfaceIds]).
 */
@Composable
private fun VariantCell3D(
    cellId: Int,
    config: YuuFaceConfig,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null,
        modifier = Modifier.aspectRatio(1f),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.Center) {
            if (YuuSurfaceIds.isLive(cellId)) {
                YuuAvatar3D(
                    configJson = config.encode(),
                    poseId = config.poseId,
                    surfaceId = cellId,
                    modifier = Modifier.fillMaxSize(),
                    fallback = {
                        YuuPlaceholder(
                            name = "…",
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                )
            } else {
                YuuPlaceholder(
                    name = "…",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun ColorSwatches(
    colors: List<Long>,
    selected: Long,
    onSelect: (Long) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(colors) { argb ->
            val active = argb == selected
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .clickable { onSelect(argb) }
                    .then(
                        if (active) {
                            Modifier.background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                CircleShape,
                            )
                        } else Modifier
                    ),
            )
        }
    }
}

@Composable
private fun FacePanel(d: YuuDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.yuu_face_shape))
        VariantGrid(count = YuuFaceConfig.FACE_SHAPES, selected = d.faceShape) { i, sel ->
            VariantCell3D(
                cellId = YuuSurfaceIds.forVariant(0, i),
                config = d.toConfig().copy(faceShape = i),
                selected = sel,
                onClick = { d.faceShape = i },
            )
        }
        SectionLabel(stringResource(R.string.yuu_skin_tone))
        ColorSwatches(YUU_SKIN_TONES, d.skinTone) { d.skinTone = it }
    }
}

@Composable
private fun BodyPanel() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.yuu_body_preview_hint))
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.yuu_body_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun HairPanel(d: YuuDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.yuu_hair_style))
        VariantGrid(count = YuuFaceConfig.HAIR_STYLES, selected = d.hairStyle) { i, sel ->
            VariantCell3D(
                cellId = YuuSurfaceIds.forVariant(1, i),
                config = d.toConfig().copy(hairStyle = i),
                selected = sel,
                onClick = { d.hairStyle = i },
            )
        }
        SectionLabel(stringResource(R.string.yuu_hair_color))
        ColorSwatches(YUU_HAIR_COLORS, d.hairColor) { d.hairColor = it }
    }
}

@Composable
private fun EyesPanel(d: YuuDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.yuu_eye_style))
        VariantGrid(count = YuuFaceConfig.EYE_STYLES, selected = d.eyeStyle) { i, sel ->
            VariantCell3D(
                cellId = YuuSurfaceIds.forVariant(2, i),
                config = d.toConfig().copy(eyeStyle = i),
                selected = sel,
                onClick = { d.eyeStyle = i },
            )
        }
        SectionLabel(stringResource(R.string.yuu_eye_color))
        ColorSwatches(YUU_EYE_COLORS, d.eyeColor) { d.eyeColor = it }
    }
}

@Composable
private fun BrowsPanel(d: YuuDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.yuu_brow_style))
        VariantGrid(count = YuuFaceConfig.BROW_STYLES, selected = d.browStyle) { i, sel ->
            VariantCell3D(
                cellId = YuuSurfaceIds.forVariant(3, i),
                config = d.toConfig().copy(browStyle = i),
                selected = sel,
                onClick = { d.browStyle = i },
            )
        }
        SectionLabel(stringResource(R.string.yuu_brow_color))
        ColorSwatches(YUU_HAIR_COLORS, d.browColor) { d.browColor = it }
    }
}

@Composable
private fun NosePanel(d: YuuDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.yuu_nose_style))
        VariantGrid(count = YuuFaceConfig.NOSE_STYLES, selected = d.noseStyle) { i, sel ->
            VariantCell3D(
                cellId = YuuSurfaceIds.forVariant(4, i),
                config = d.toConfig().copy(noseStyle = i),
                selected = sel,
                onClick = { d.noseStyle = i },
            )
        }
    }
}

@Composable
private fun MouthPanel(d: YuuDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.yuu_mouth_style))
        VariantGrid(count = YuuFaceConfig.MOUTH_STYLES, selected = d.mouthStyle) { i, sel ->
            VariantCell3D(
                cellId = YuuSurfaceIds.forVariant(5, i),
                config = d.toConfig().copy(mouthStyle = i),
                selected = sel,
                onClick = { d.mouthStyle = i },
            )
        }
        SectionLabel(stringResource(R.string.yuu_mouth_color))
        ColorSwatches(YUU_MOUTH_COLORS, d.mouthColor) { d.mouthColor = it }
    }
}

@Composable
private fun ExtrasPanel(d: YuuDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.yuu_glasses))
        VariantGrid(count = YuuFaceConfig.GLASSES_STYLES + 1, selected = d.glassesStyle) { i, sel ->
            if (i == 0) {
                VariantCell(sel, onClick = { d.glassesStyle = i }) {
                    Text(stringResource(UiR.string.none))
                }
            } else {
                VariantCell3D(
                    cellId = YuuSurfaceIds.forVariant(6, i),
                    config = d.toConfig().copy(glassesStyle = i),
                    selected = sel,
                    onClick = { d.glassesStyle = i },
                )
            }
        }
        SectionLabel(stringResource(R.string.yuu_beard))
        VariantGrid(count = YuuFaceConfig.BEARD_STYLES + 1, selected = d.beardStyle) { i, sel ->
            if (i == 0) {
                VariantCell(sel, onClick = { d.beardStyle = i }) {
                    Text(stringResource(UiR.string.none))
                }
            } else {
                VariantCell3D(
                    cellId = YuuSurfaceIds.forVariant(7, i),
                    config = d.toConfig().copy(beardStyle = i),
                    selected = sel,
                    onClick = { d.beardStyle = i },
                )
            }
        }
        SectionLabel(stringResource(R.string.yuu_mole))
        VariantGrid(count = YuuFaceConfig.MOLE_STYLES + 1, selected = d.moleStyle) { i, sel ->
            if (i == 0) {
                VariantCell(sel, onClick = { d.moleStyle = i }) {
                    Text(stringResource(UiR.string.none))
                }
            } else {
                VariantCell3D(
                    cellId = YuuSurfaceIds.forVariant(8, i),
                    config = d.toConfig().copy(moleStyle = i),
                    selected = sel,
                    onClick = { d.moleStyle = i },
                )
            }
        }
        SectionLabel(stringResource(R.string.yuu_wrinkles))
        VariantGrid(count = YuuFaceConfig.WRINKLE_STYLES + 1, selected = d.wrinkleStyle) { i, sel ->
            if (i == 0) {
                VariantCell(sel, onClick = { d.wrinkleStyle = i }) {
                    Text(stringResource(UiR.string.none))
                }
            } else {
                VariantCell3D(
                    cellId = YuuSurfaceIds.forVariant(9, i),
                    config = d.toConfig().copy(wrinkleStyle = i),
                    selected = sel,
                    onClick = { d.wrinkleStyle = i },
                )
            }
        }
        SectionLabel(stringResource(R.string.yuu_blush))
        VariantGrid(count = YuuFaceConfig.BLUSH_STYLES + 1, selected = d.blushStyle) { i, sel ->
            if (i == 0) {
                VariantCell(sel, onClick = { d.blushStyle = i }) {
                    Text(stringResource(UiR.string.none))
                }
            } else {
                VariantCell3D(
                    cellId = YuuSurfaceIds.forVariant(10, i),
                    config = d.toConfig().copy(blushStyle = i),
                    selected = sel,
                    onClick = { d.blushStyle = i },
                )
            }
        }
    }
}

