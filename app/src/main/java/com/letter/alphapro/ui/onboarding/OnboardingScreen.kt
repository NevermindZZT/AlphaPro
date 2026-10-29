package com.letter.alphapro.ui.onboarding

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.letter.alphapro.R
import com.letter.alphapro.data.OnboardingExit
import com.letter.alphapro.data.OnboardingExitReason
import com.letter.alphapro.ui.components.*
import com.letter.alphapro.ui.theme.AlphaOrangePrimary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private data class OnboardingPageData(
    @StringRes val badgeRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    @StringRes val feat1Res: Int,
    @StringRes val feat2Res: Int,
    @StringRes val feat3Res: Int,
    val feat1Icon: ImageVector,
    val feat2Icon: ImageVector,
    val feat3Icon: ImageVector,
    val animation: @Composable () -> Unit
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onExit: (OnboardingExit) -> Unit,
    modifier: Modifier = Modifier,
    isReplay: Boolean = false,
    actionsEnabled: Boolean = true
) {
    val coroutineScope = rememberCoroutineScope()
    val pages = remember {
        listOf(
            OnboardingPageData(
                badgeRes = R.string.onboarding_page1_badge,
                titleRes = R.string.onboarding_page1_title,
                descRes = R.string.onboarding_page1_desc,
                feat1Res = R.string.onboarding_page1_feat1,
                feat2Res = R.string.onboarding_page1_feat2,
                feat3Res = R.string.onboarding_page1_feat3,
                feat1Icon = Icons.Rounded.Favorite,
                feat2Icon = Icons.Rounded.Schedule,
                feat3Icon = Icons.Rounded.Route,
                animation = { OnboardingGpsAnimation() }
            ),
            OnboardingPageData(
                badgeRes = R.string.onboarding_page2_badge,
                titleRes = R.string.onboarding_page2_title,
                descRes = R.string.onboarding_page2_desc,
                feat1Res = R.string.onboarding_page2_feat1,
                feat2Res = R.string.onboarding_page2_feat2,
                feat3Res = R.string.onboarding_page2_feat3,
                feat1Icon = Icons.Rounded.PhotoLibrary,
                feat2Icon = Icons.Rounded.CenterFocusStrong,
                feat3Icon = Icons.Rounded.Transform,
                animation = { OnboardingGalleryAnimation() }
            ),
            OnboardingPageData(
                badgeRes = R.string.onboarding_page3_badge,
                titleRes = R.string.onboarding_page3_title,
                descRes = R.string.onboarding_page3_desc,
                feat1Res = R.string.onboarding_page3_feat1,
                feat2Res = R.string.onboarding_page3_feat2,
                feat3Res = R.string.onboarding_page3_feat3,
                feat1Icon = Icons.Rounded.RadioButtonChecked,
                feat2Icon = Icons.Rounded.Timelapse,
                feat3Icon = Icons.Rounded.Tune,
                animation = { OnboardingRemoteAnimation() }
            ),
            OnboardingPageData(
                badgeRes = R.string.onboarding_page4_badge,
                titleRes = R.string.onboarding_page4_title,
                descRes = R.string.onboarding_page4_desc,
                feat1Res = R.string.onboarding_page4_feat1,
                feat2Res = R.string.onboarding_page4_feat2,
                feat3Res = R.string.onboarding_page4_feat3,
                feat1Icon = Icons.Rounded.Wifi,
                feat2Icon = Icons.Rounded.Dns,
                feat3Icon = Icons.Rounded.Sync,
                animation = { OnboardingTetherAnimation() }
            )
        )
    }

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val latestOnExit by rememberUpdatedState(onExit)
    val requestExit: (OnboardingExitReason) -> Unit = { reason ->
        latestOnExit(OnboardingExit(reason, pagerState.settledPage))
    }

    var backProgress by remember { mutableFloatStateOf(0f) }
    val shouldInterceptBack = actionsEnabled && !isReplay && pagerState.currentPage > 0

    PredictiveBackHandler(enabled = shouldInterceptBack) { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { backEvent ->
                backProgress = backEvent.progress
            }
            backProgress = 0f
            pagerState.animateScrollToPage(pagerState.currentPage - 1)
        } catch (_: CancellationException) {
            backProgress = 0f
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.statusBars,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = AlphaOrangePrimary,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Camera,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "AlphaPro",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    },
                    navigationIcon = {
                        if (pagerState.currentPage > 0) {
                            AlphaIconButton(
                                enabled = actionsEnabled,
                                onClick = {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(R.string.onboarding_prev),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    },
                    actions = {
                        if (isReplay) {
                            AlphaIconButton(onClick = { requestExit(OnboardingExitReason.CLOSE_REPLAY) }, enabled = actionsEnabled) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.onboarding_close),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        } else if (pagerState.currentPage < pages.lastIndex) {
                            AlphaTextButton(onClick = { requestExit(OnboardingExitReason.SKIP) }, enabled = actionsEnabled) {
                                Text(stringResource(R.string.onboarding_skip))
                            }
                        }
                    }
                )
            },
            bottomBar = {
                Surface(
                    tonalElevation = 3.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                    ) {
                        // Animated Page Indicator Pills
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            repeat(pages.size) { index ->
                                val isSelected = pagerState.currentPage == index
                                val width by animateDpAsState(
                                    targetValue = if (isSelected) 24.dp else 8.dp,
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                    label = "dot_width"
                                )
                                val dotColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .height(8.dp)
                                        .width(width)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // Navigation Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (pagerState.currentPage > 0) {
                                AlphaOutlinedButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = actionsEnabled,
                                    size = AlphaButtonSize.Large
                                ) {
                                    Text(stringResource(R.string.onboarding_prev))
                                }
                            }

                            val isLast = pagerState.currentPage == pages.lastIndex
                            AlphaButton(
                                onClick = {
                                    if (isLast) {
                                        requestExit(OnboardingExitReason.FINISH)
                                    } else {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                        }
                                    }
                                },
                                modifier = Modifier.weight(if (pagerState.currentPage > 0) 1.5f else 1f),
                                enabled = actionsEnabled && !pagerState.isScrollInProgress,
                                size = AlphaButtonSize.Large
                            ) {
                                Text(
                                    stringResource(
                                        when {
                                            isLast && isReplay -> R.string.onboarding_done
                                            isLast -> R.string.onboarding_start
                                            else -> R.string.onboarding_next
                                        }
                                    )
                                )
                                if (!isLast) {
                                    Spacer(Modifier.width(AlphaButtonDefaults.IconSpacing))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(AlphaButtonDefaults.IconSize)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = actionsEnabled,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .graphicsLayer {
                        if (backProgress > 0f) {
                            val scale = 1f - (backProgress * 0.08f)
                            scaleX = scale
                            scaleY = scale
                            alpha = 1f - (backProgress * 0.2f)
                        }
                    }
            ) { pageIndex ->
                val page = pages[pageIndex]
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Feature Animated Illustration Area
                    page.animation()

                    Spacer(Modifier.height(16.dp))

                    // Badge Pill
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Text(
                            text = stringResource(page.badgeRes),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Feature Headline Title
                    Text(
                        text = stringResource(page.titleRes),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    // Feature In-depth Description
                    Text(
                        text = stringResource(page.descRes),
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(14.dp))

                    // Feature Highlights Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FeatureItem(icon = page.feat1Icon, text = stringResource(page.feat1Res))
                            FeatureItem(icon = page.feat2Icon, text = stringResource(page.feat2Res))
                            FeatureItem(icon = page.feat3Icon, text = stringResource(page.feat3Res))
                        }
                    }

                    if (pageIndex == 0) {
                        Spacer(Modifier.height(14.dp))
                        BackgroundSyncSetupCard(enabled = actionsEnabled)
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun FeatureItem(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
