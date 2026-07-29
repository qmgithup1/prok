package com.systemsgo.hex.ui.screens.addconnection

/**
 * ADD-CONNECTION PROTOCOL PICKER (Part 1/2).
 *
 * First screen shown after the user taps "Add Connection" (see HomeScreen's
 * onNewConnection — Part 2 rewires that callback to navigate here instead of
 * straight to the connection editor).
 *
 * Visual language follows the agreed glassmorphism + Material 3 (2026)
 * reference: translucent 20–24dp-rounded cards in a 2-column grid, a
 * color-coded icon chip per protocol, a category badge pill at the bottom
 * of each card, and compact icon+label filter chips.
 *
 * Covers: top app bar with search, the filter-chip row, "Recently Used",
 * "Popular", the "All Protocols" grid grouped by category, the empty-search
 * state, the [ProtocolCard] shared by every section, and the bottom
 * "Can't find what you need?" CTA.
 *
 * PART 2: this screen is now wired into navigation — see
 * com.systemsgo.hex.ui.screens.addconnection.AddConnectionRoute, which hosts
 * it under the "add_connection_protocol" route, owns [onProtocolChosen]
 * (dispatch to the connection editor when [ProtocolCatalogEntry.protocolType]
 * is non-null, or to [RequestProtocolSheet] otherwise), and answers
 * [onRequestProtocol] for the bottom CTA below.
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.PrecisionManufacturing
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.systemsgo.hex.R
import com.systemsgo.hex.ui.coachmark.CoachMarkOverlay
import com.systemsgo.hex.ui.coachmark.CoachMarkShape
import com.systemsgo.hex.ui.coachmark.CoachMarkState
import com.systemsgo.hex.ui.coachmark.CoachMarkStep
import com.systemsgo.hex.ui.coachmark.coachMarkTarget
import com.systemsgo.hex.ui.coachmark.rememberCoachMarkState
import com.systemsgo.hex.data.model.ProtocolBadge
import com.systemsgo.hex.data.model.ProtocolCatalogEntry
import com.systemsgo.hex.data.model.ProtocolCategory
import com.systemsgo.hex.ui.theme.CardBorderColor
import com.systemsgo.hex.ui.theme.ChipBg
import com.systemsgo.hex.ui.theme.CometTail
import com.systemsgo.hex.ui.theme.DeepSpace
import com.systemsgo.hex.ui.theme.InputBg
import com.systemsgo.hex.ui.theme.InputBorder
import com.systemsgo.hex.ui.theme.NebulaSurface
import com.systemsgo.hex.ui.theme.PlasmaGreen
import com.systemsgo.hex.ui.theme.PulsarCyan
import com.systemsgo.hex.ui.theme.QuantumBlue
import com.systemsgo.hex.ui.theme.SolarFlare
import com.systemsgo.hex.ui.theme.StarDust
import com.systemsgo.hex.ui.theme.VoidPurple

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddConnectionProtocolScreen(
    onBack: () -> Unit,
    onProtocolChosen: (ProtocolCatalogEntry) -> Unit,
    // PART 2: the bottom "Can't find what you need?" CTA now actually does
    // something — see AddConnectionRoute, which opens RequestProtocolSheet
    // (generic, entry = null) here.
    onRequestProtocol: () -> Unit = {},
    // TOP-BAR QUICK ACTIONS (v2 — replaces the old + button chooser dialog).
    // See AddConnectionTopBar's doc comment and AddConnectionRoute.kt, which
    // owns the actual launchers/permissions/navigation behind these.
    onScanQr: () -> Unit = {},
    onImportFile: () -> Unit = {},
    onMoreOptions: () -> Unit = {},
    viewModel: AddConnectionProtocolViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var introTarget by remember { mutableStateOf<ProtocolCatalogEntry?>(null) }

    // First-time experience gate: the very first tap on a given protocol opens
    // ProtocolIntroPanel instead of handing off immediately; every tap after
    // that (hasSeenIntro == true) goes straight to onProtocolChosen.
    val handleProtocolClick: (ProtocolCatalogEntry) -> Unit = { entry ->
        if (viewModel.hasSeenIntro(entry)) {
            viewModel.recordUsed(entry)
            onProtocolChosen(entry)
        } else {
            introTarget = entry
        }
    }

    // "How to create a connection" spotlight tour — runs once, the very
    // first time this screen is reached, walking the user through
    // search → tap a protocol → (or) request one that's missing.
    val coachMarkState = rememberCoachMarkState()
    // stringResource() is @Composable — resolved here, in composable scope,
    // then handed as plain Strings into the LaunchedEffect below (whose
    // block is a suspend lambda and can't call @Composable functions).
    val searchTitle = stringResource(R.string.add_connection_spotlight_search_title)
    val searchBody = stringResource(R.string.add_connection_spotlight_search_body)
    val cardTitle = stringResource(R.string.add_connection_spotlight_card_title)
    val cardBody = stringResource(R.string.add_connection_spotlight_card_body)
    val requestTitle = stringResource(R.string.add_connection_spotlight_request_title)
    val requestBody = stringResource(R.string.add_connection_spotlight_request_body)

    LaunchedEffect(Unit) {
        if (viewModel.shouldShowConnectionSpotlight()) {
            coachMarkState.start(
                listOf(
                    CoachMarkStep(
                        targetKey = CoachMarkTargets.SEARCH,
                        title = searchTitle,
                        description = searchBody,
                    ),
                    CoachMarkStep(
                        targetKey = CoachMarkTargets.FIRST_PROTOCOL_CARD,
                        title = cardTitle,
                        description = cardBody,
                        shape = CoachMarkShape.RoundedRect(cornerRadius = 22.dp),
                    ),
                    CoachMarkStep(
                        targetKey = CoachMarkTargets.REQUEST_PROTOCOL,
                        title = requestTitle,
                        description = requestBody,
                    ),
                ),
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = DeepSpace,
        topBar = {
            AddConnectionTopBar(
                searchQuery = uiState.searchQuery,
                onSearchQueryChange = viewModel::onSearchQueryChange,
                onBack = onBack,
                onScanQr = onScanQr,
                onImportFile = onImportFile,
                onMoreOptions = onMoreOptions,
                coachMarkState = coachMarkState,
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            FilterChipRow(selected = uiState.selectedFilter, onFilterSelected = viewModel::onFilterSelected)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                if (uiState.isEmpty) {
                    EmptySearchState(modifier = Modifier.padding(top = 48.dp))
                } else {
                    if (uiState.recentEntries.isNotEmpty() && !uiState.isSearchActive) {
                        ProtocolCardRowSection(
                            title = stringResource(R.string.add_connection_protocol_recently_used),
                            entries = uiState.recentEntries,
                            favoriteIds = uiState.favoriteIds,
                            onToggleFavorite = viewModel::toggleFavorite,
                            onClick = handleProtocolClick,
                        )
                    }

                    if (!uiState.isSearchActive && uiState.selectedFilter == ProtocolFilterChip.All) {
                        ProtocolCardRowSection(
                            title = stringResource(R.string.add_connection_protocol_popular),
                            entries = uiState.popularEntries,
                            favoriteIds = uiState.favoriteIds,
                            onToggleFavorite = viewModel::toggleFavorite,
                            onClick = handleProtocolClick,
                            coachMarkState = coachMarkState,
                        )
                    }

                    // "All Protocols" — 2-column grid, grouped by category, each group
                    // headed by its title + a thin divider, exactly like the reference design.
                    uiState.groupedEntries.forEach { (category, entries) ->
                        ProtocolCategoryGridSection(
                            category = category,
                            entries = entries,
                            favoriteIds = uiState.favoriteIds,
                            onToggleFavorite = viewModel::toggleFavorite,
                            onClick = handleProtocolClick,
                        )
                    }
                }

                RequestProtocolCta(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .coachMarkTarget(CoachMarkTargets.REQUEST_PROTOCOL, coachMarkState),
                    onClick = onRequestProtocol,
                )
            }
        }
    }

    introTarget?.let { entry ->
        ProtocolIntroPanel(
            entry = entry,
            onDismiss = { introTarget = null },
            onContinue = {
                viewModel.markIntroSeen(entry)
                viewModel.recordUsed(entry)
                introTarget = null
                onProtocolChosen(entry)
            },
        )
    }

    CoachMarkOverlay(
        state = coachMarkState,
        nextLabel = stringResource(R.string.coach_mark_next),
        doneLabel = stringResource(R.string.coach_mark_done),
        skipLabel = stringResource(R.string.coach_mark_skip),
        onFinished = viewModel::markConnectionSpotlightSeen,
    )
    }
}

/** Target keys this screen registers for [coachMarkTarget] — kept together so the tour steps above stay in sync. */
private object CoachMarkTargets {
    const val SEARCH = "add_connection_search"
    const val FIRST_PROTOCOL_CARD = "add_connection_first_protocol_card"
    const val REQUEST_PROTOCOL = "add_connection_request_protocol"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddConnectionTopBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    // TOP-BAR QUICK ACTIONS: the old "+" chooser sheet (New / Import / Scan
    // QR / Discover / Web Feed / Quick Transfer) added an extra tap in front
    // of the picker below for no real benefit, so Import and Scan QR now
    // live directly here as top-bar actions — the very first thing the user
    // sees on this screen, one tap away, instead of behind a dialog. See
    // AddConnectionRoute.kt for where the launchers/permission handling
    // that used to live in HomeScreen now live for this screen instead.
    onScanQr: () -> Unit = {},
    onImportFile: () -> Unit = {},
    // The 3 remaining chooser options (Discover Devices / Web Feed / Quick
    // Transfer) are less common than New/Import/Scan, so they stay tucked
    // behind a single "more" overflow icon here rather than crowding the
    // top bar with 5 icons.
    onMoreOptions: () -> Unit = {},
    coachMarkState: CoachMarkState? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DeepSpace)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(NebulaSurface),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = StarDust)
            }
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(text = stringResource(R.string.add_connection), color = StarDust, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(text = stringResource(R.string.add_connection_protocol_subtitle), color = CometTail, fontSize = 13.sp)
            }

            IconButton(
                onClick = onScanQr,
                modifier = Modifier.size(40.dp).clip(CircleShape).background(NebulaSurface),
            ) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = stringResource(R.string.cd_scan_qr), tint = SolarFlare)
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onImportFile,
                modifier = Modifier.size(40.dp).clip(CircleShape).background(NebulaSurface),
            ) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = stringResource(R.string.cd_import_file), tint = VoidPurple)
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onMoreOptions,
                modifier = Modifier.size(40.dp).clip(CircleShape).background(NebulaSurface),
            ) {
                Icon(Icons.Filled.MoreHoriz, contentDescription = stringResource(R.string.cd_more_add_options), tint = StarDust)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .let { base ->
                    if (coachMarkState != null) base.coachMarkTarget(CoachMarkTargets.SEARCH, coachMarkState) else base
                },
            placeholder = { Text(stringResource(R.string.add_connection_protocol_search_placeholder), color = CometTail) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = CometTail) },
            trailingIcon = {
                AnimatedVisibility(visible = searchQuery.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cd_clear_search), tint = CometTail)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = InputBg,
                unfocusedContainerColor = InputBg,
                focusedBorderColor = PulsarCyan,
                unfocusedBorderColor = InputBorder,
                focusedTextColor = StarDust,
                unfocusedTextColor = StarDust,
                cursorColor = PulsarCyan,
            ),
        )
    }
}

/** Compact icon+label chips (All / Desktop / System / Network / ⋯) matching the reference design. */
@Composable
private fun FilterChipRow(
    selected: ProtocolFilterChip,
    onFilterSelected: (ProtocolFilterChip) -> Unit,
) {
    // Primary row the reference shows inline; the rest are reachable behind "More".
    val primary = listOf(
        ProtocolFilterChip.All,
        ProtocolFilterChip.Category(ProtocolCategory.DESKTOP),
        ProtocolFilterChip.Category(ProtocolCategory.TERMINAL),
        ProtocolFilterChip.Category(ProtocolCategory.HARDWARE),
        ProtocolFilterChip.Category(ProtocolCategory.NETWORK),
        ProtocolFilterChip.Category(ProtocolCategory.VIRTUALIZATION),
    )
    var showAllChips by remember { mutableStateOf(false) }
    val chips = if (showAllChips) ProtocolFilterChip.all else primary

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chips, key = { it.label }) { chip ->
            FilterChipPill(
                label = chip.label,
                icon = filterChipIconFor(chip),
                isSelected = chip == selected,
                onClick = { onFilterSelected(chip) },
            )
        }
        if (!showAllChips) {
            item(key = "__more__") {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(ChipBg)
                        .border(BorderStroke(1.dp, InputBorder), CircleShape)
                        .clickable { showAllChips = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.MoreHoriz, contentDescription = stringResource(R.string.add_connection_protocol_more_categories), tint = CometTail)
                }
            }
        }
    }
}

@Composable
private fun FilterChipPill(label: String, icon: ImageVector, isSelected: Boolean, onClick: () -> Unit) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) PulsarCyan.copy(alpha = 0.18f) else ChipBg,
        animationSpec = tween(200), label = "chipBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) PulsarCyan else InputBorder,
        animationSpec = tween(200), label = "chipBorder",
    )
    val contentColor = if (isSelected) PulsarCyan else CometTail

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(backgroundColor)
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
        Text(text = label, color = contentColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun filterChipIconFor(chip: ProtocolFilterChip): ImageVector = when (chip) {
    ProtocolFilterChip.All -> Icons.Filled.Check
    ProtocolFilterChip.Favorites -> Icons.Filled.Star
    ProtocolFilterChip.Recent -> Icons.Outlined.Tune
    is ProtocolFilterChip.Category -> protocolIconFor(chip.category)
}

@Composable
private fun ProtocolCardRowSection(
    title: String,
    entries: List<ProtocolCatalogEntry>,
    favoriteIds: Set<String>,
    onToggleFavorite: (ProtocolCatalogEntry) -> Unit,
    onClick: (ProtocolCatalogEntry) -> Unit,
    coachMarkState: CoachMarkState? = null,
) {
    if (entries.isEmpty()) return
    Column(modifier = Modifier.padding(top = 20.dp)) {
        SectionHeader(title)
        Spacer(modifier = Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                ProtocolCard(
                    entry = entry,
                    isFavorite = entry.id in favoriteIds,
                    onToggleFavorite = { onToggleFavorite(entry) },
                    onClick = { onClick(entry) },
                    modifier = Modifier
                        .width(168.dp)
                        .let { base ->
                            if (index == 0 && coachMarkState != null) {
                                base.coachMarkTarget(CoachMarkTargets.FIRST_PROTOCOL_CARD, coachMarkState)
                            } else base
                        },
                )
            }
        }
    }
}

/**
 * A category group inside "All Protocols" — title + divider, then entries laid
 * out as a non-lazy 2-column grid (groups are small; the screen itself already
 * scrolls via the outer Column, so a nested LazyVerticalGrid isn't needed here).
 */
@Composable
private fun ProtocolCategoryGridSection(
    category: ProtocolCategory,
    entries: List<ProtocolCatalogEntry>,
    favoriteIds: Set<String>,
    onToggleFavorite: (ProtocolCatalogEntry) -> Unit,
    onClick: (ProtocolCatalogEntry) -> Unit,
) {
    if (entries.isEmpty()) return
    Column(modifier = Modifier.padding(top = 24.dp, start = 16.dp, end = 16.dp)) {
        SectionHeader(categoryGroupTitle(category))
        Spacer(modifier = Modifier.height(2.dp))
        HorizontalDivider(color = InputBorder, thickness = 1.dp)
        Spacer(modifier = Modifier.height(12.dp))

        entries.chunked(2).forEach { rowEntries ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowEntries.forEach { entry ->
                    ProtocolCard(
                        entry = entry,
                        isFavorite = entry.id in favoriteIds,
                        onToggleFavorite = { onToggleFavorite(entry) },
                        onClick = { onClick(entry) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowEntries.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/** Section-header titles group differently for "All Protocols" than the flat [ProtocolCategory] label. */
@Composable
private fun categoryGroupTitle(category: ProtocolCategory): String = when (category) {
    ProtocolCategory.DESKTOP -> stringResource(R.string.add_connection_protocol_category_desktop)
    ProtocolCategory.HARDWARE -> stringResource(R.string.add_connection_protocol_category_hardware)
    ProtocolCategory.FILE_TRANSFER -> stringResource(R.string.add_connection_protocol_category_file_transfer)
    ProtocolCategory.TERMINAL -> stringResource(R.string.add_connection_protocol_category_terminal)
    ProtocolCategory.NETWORK -> stringResource(R.string.add_connection_protocol_category_network)
    ProtocolCategory.VIRTUALIZATION -> stringResource(R.string.add_connection_protocol_category_virtualization)
    ProtocolCategory.INDUSTRIAL -> stringResource(R.string.add_connection_protocol_category_industrial)
    ProtocolCategory.CAMERAS -> stringResource(R.string.add_connection_protocol_category_cameras)
    ProtocolCategory.WAKE_ON_LAN -> stringResource(R.string.add_connection_protocol_category_wake_on_lan)
    ProtocolCategory.MONITORING -> stringResource(R.string.add_connection_protocol_category_monitoring)
    ProtocolCategory.WEB -> stringResource(R.string.add_connection_protocol_category_web)
    else -> category.label
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = StarDust,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/**
 * Premium glassmorphism protocol card: translucent surface, soft border glow,
 * color-coded icon chip, name + description, and a category badge pill at the
 * bottom — matches the agreed 2026 reference design. Reused by Recently Used,
 * Popular, All Protocols, and search results.
 */
@Composable
fun ProtocolCard(
    entry: ProtocolCatalogEntry,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val elevation by animateDpAsState(targetValue = if (isPressed) 1.dp else 6.dp, label = "cardElevation")
    val accent = protocolAccentFor(entry.category)
    // Not-yet-supported cards stay tappable (routes to RequestProtocolSheet — see
    // AddConnectionRoute) but read as visually distinct from working protocols.
    val isComingSoon = entry.launchKind == com.systemsgo.hex.data.model.ProtocolLaunchKind.NOT_YET_SUPPORTED
    val contentAlpha = if (isComingSoon) 0.6f else 1f

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.verticalGradient(
                    listOf(NebulaSurface.copy(alpha = 0.9f), NebulaSurface.copy(alpha = 0.65f)),
                ),
            )
            .border(BorderStroke(1.dp, CardBorderColor.copy(alpha = if (isComingSoon) 0.12f else 0.25f)), RoundedCornerShape(22.dp))
            .let { if (isComingSoon) it.alpha(contentAlpha) else it }
            .clickable(
                interactionSource = interactionSource,
                indication = rememberRipple(),
                onClick = onClick,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = protocolIconFor(entry.category),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(26.dp),
                    )
                }
                // UX FIX: was a bare 22dp Icon with .clickable directly on
                // it — a touch target well under Android's 48dp minimum.
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(40.dp)) {
                    if (isFavorite) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = stringResource(R.string.remove_from_favorites),
                            tint = SolarFlare,
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.StarBorder,
                            contentDescription = stringResource(R.string.add_to_favorites),
                            tint = CometTail.copy(alpha = 0.5f),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = entry.name,
                color = StarDust,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = entry.description,
                color = CometTail,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 15.sp,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CategoryBadgePill(entry.category)
                entry.badges.forEach { badge -> ProtocolBadgeChip(badge) }
                if (isComingSoon) ProtocolBadgeChip(ProtocolBadge.COMING_SOON)
            }
        }
    }
}

@Composable
private fun CategoryBadgePill(category: ProtocolCategory) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(ChipBg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text = category.label, color = CometTail, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProtocolBadgeChip(badge: ProtocolBadge) {
    val (label, color) = when (badge) {
        ProtocolBadge.NEW -> stringResource(R.string.add_connection_protocol_badge_new) to PulsarCyan
        ProtocolBadge.BETA -> stringResource(R.string.add_connection_protocol_badge_beta) to SolarFlare
        ProtocolBadge.POPULAR -> stringResource(R.string.add_connection_protocol_badge_popular) to VoidPurple
        ProtocolBadge.ENTERPRISE -> stringResource(R.string.add_connection_protocol_badge_enterprise) to VoidPurple
        ProtocolBadge.COMING_SOON -> stringResource(R.string.add_connection_protocol_badge_coming_soon) to CometTail
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text = label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EmptySearchState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.SearchOff,
            contentDescription = null,
            tint = CometTail.copy(alpha = 0.6f),
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = stringResource(R.string.add_connection_protocol_empty_title), color = StarDust, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = stringResource(R.string.add_connection_protocol_empty_subtitle), color = CometTail, fontSize = 13.sp)
    }
}

@Composable
private fun RequestProtocolCta(modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Row(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(NebulaSurface.copy(alpha = 0.7f))
            .border(BorderStroke(1.dp, InputBorder), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = PulsarCyan, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.add_connection_protocol_request_cta_title), color = StarDust, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(text = stringResource(R.string.add_connection_protocol_request_cta_subtitle), color = CometTail, fontSize = 12.sp)
        }
    }
}

internal fun protocolIconFor(category: ProtocolCategory): ImageVector = when (category) {
    ProtocolCategory.DESKTOP -> Icons.Outlined.DesktopWindows
    ProtocolCategory.TERMINAL -> Icons.Outlined.Terminal
    ProtocolCategory.FILE_TRANSFER -> Icons.Outlined.FolderShared
    ProtocolCategory.VIRTUALIZATION -> Icons.Outlined.Monitor
    ProtocolCategory.WEB -> Icons.Outlined.Web
    ProtocolCategory.NETWORK -> Icons.Outlined.SettingsRemote
    ProtocolCategory.HARDWARE -> Icons.Outlined.Memory
    ProtocolCategory.MONITORING -> Icons.Outlined.Dns
    ProtocolCategory.INDUSTRIAL -> Icons.Outlined.PrecisionManufacturing
    ProtocolCategory.CAMERAS -> Icons.Outlined.Videocam
    ProtocolCategory.WAKE_ON_LAN -> Icons.Outlined.Bolt
    ProtocolCategory.OTHER, ProtocolCategory.FAVORITES, ProtocolCategory.RECENT -> Icons.Outlined.DesktopWindows
}

/** Per-category accent color for each card's icon chip — gives the grid the varied palette from the reference. */
@Composable
internal fun protocolAccentFor(category: ProtocolCategory): Color = when (category) {
    ProtocolCategory.DESKTOP -> PulsarCyan
    ProtocolCategory.TERMINAL -> PlasmaGreen
    ProtocolCategory.FILE_TRANSFER -> SolarFlare
    ProtocolCategory.VIRTUALIZATION -> QuantumBlue
    ProtocolCategory.WEB -> QuantumBlue
    ProtocolCategory.NETWORK -> PlasmaGreen
    ProtocolCategory.HARDWARE -> SolarFlare
    ProtocolCategory.MONITORING -> PulsarCyan
    ProtocolCategory.INDUSTRIAL -> VoidPurple
    ProtocolCategory.CAMERAS -> VoidPurple
    ProtocolCategory.WAKE_ON_LAN -> SolarFlare
    ProtocolCategory.OTHER, ProtocolCategory.FAVORITES, ProtocolCategory.RECENT -> PulsarCyan
}
