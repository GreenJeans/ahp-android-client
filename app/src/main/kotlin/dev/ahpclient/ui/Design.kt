package dev.ahpclient.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The frame every screen sits in: a top bar that is large and collapses as the content scrolls (or a small pinned one),
 * a back arrow when there is somewhere to go back to, and the content held to a readable width. The system bars' insets
 * are applied here, so nothing draws under the status or navigation bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back",
    backTag: String = "back",
    titleTag: String? = null,
    subtitle: String? = null,
    large: Boolean = true,
    barState: TopAppBarState = rememberTopAppBarState(),
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val scrollBehavior = if (large) TopAppBarDefaults.exitUntilCollapsedScrollBehavior(state = barState) else TopAppBarDefaults.pinnedScrollBehavior(state = barState)
    val titleText: @Composable () -> Unit = {
        val name: @Composable () -> Unit = {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (titleTag != null) Modifier.testTag(titleTag) else Modifier,
            )
        }
        // A second line (the state, say) only on the small bar: the large one collapses into it and has no room for two.
        if (subtitle != null && !large) {
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (titleTag != null) Modifier.testTag(titleTag) else Modifier,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("subtitle"),
                )
            }
        } else {
            name()
        }
    }
    val back: @Composable () -> Unit = {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.testTag(backTag)) {
                Icon(AppIcons.ArrowBack, contentDescription = backLabel)
            }
        }
    }
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surface,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (large) {
                LargeTopAppBar(title = titleText, navigationIcon = back, actions = actions, colors = colors, scrollBehavior = scrollBehavior)
            } else {
                TopAppBar(title = titleText, navigationIcon = back, actions = actions, colors = colors, scrollBehavior = scrollBehavior)
            }
        },
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = Design.ContentMaxWidth).fillMaxSize()) { content() }
        }
    }
}

/** The shape language shared by every screen: big outer corners, small inner ones, a thin gap between neighbours. */
object Design {
    val OuterRadius = 24.dp
    val InnerRadius = 6.dp
    val Gap = 3.dp
    val ScreenPadding = 16.dp

    /** The widest a column of content grows on a tablet or a landscape phone. */
    val ContentMaxWidth = 720.dp
}

/**
 * The shape of item [index] of [count] in a stack of cards: the first has big top corners, the last big bottom corners,
 * and the ones between small corners all round, so the stack reads as one group split into pieces.
 */
fun connectedShape(index: Int, count: Int): Shape {
    val big = Design.OuterRadius
    val small = Design.InnerRadius
    return when {
        count <= 1 -> RoundedCornerShape(big)
        index <= 0 -> RoundedCornerShape(topStart = big, topEnd = big, bottomStart = small, bottomEnd = small)
        index >= count - 1 -> RoundedCornerShape(topStart = small, topEnd = small, bottomStart = big, bottomEnd = big)
        else -> RoundedCornerShape(small)
    }
}

/**
 * One card of a stack. Clickable when [onClick] is given, and then the whole card is the target. [modifier] goes on the
 * card's content, the same node that takes the click, so a test tag or merged semantics put on it describe the control.
 */
@Composable
fun TileCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Design.OuterRadius),
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable () -> Unit,
) {
    Surface(
        color = color,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = shape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = modifier.fillMaxWidth().let {
                if (onClick != null) it.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else it
            },
        ) { content() }
    }
}

/** A heading that names the group of cards under it. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** An icon in a tonal circle-ish square, the leading part of a settings tile or a row. */
@Composable
fun TileIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(10.dp).size(24.dp))
    }
}

/** A card laid out as [leading] icon, a title with an optional subtitle, and an optional [trailing] part. */
@Composable
fun TileRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) TileIcon(leading)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

/** What a screen says when it has nothing to list: an icon, a line, and what to do about it. */
@Composable
fun EmptyState(icon: ImageVector, title: String, modifier: Modifier = Modifier, body: String? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
