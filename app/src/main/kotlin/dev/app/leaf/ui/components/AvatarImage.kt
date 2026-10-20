package dev.app.leaf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import dev.app.leaf.LocalAvatarProvider
import dev.app.leaf.domain.avatars.avatarColorArgb
import dev.app.leaf.domain.extensions.sha256
import dev.app.leaf.domain.models.Identity
import org.eclipse.jgit.lib.PersonIdent

/**
 * A person's avatar: their picture, or when there is none the initial on a fill. Without a [color] (fork-only, upstream
 * issue 231) the fill is the person's own, from `AVATAR_PALETTE`, which is the same everywhere they show up: the log,
 * the details footer, file history. Only the theme's primary is left for someone with no name and no email. A [color]
 * is for what isn't a person's colour, such as the grey of the committer's badge.
 */
@Composable
fun AvatarImage(
    modifier: Modifier = Modifier,
    personIdent: Identity,
    shape: Shape = CircleShape,
    color: Color? = null,
) {
    val current = LocalAvatarProvider.current
    val primary = MaterialTheme.colors.primary
    val fill = color ?: remember(personIdent, primary) { personIdent.avatarColorArgb()?.let { Color(it) } ?: primary }

    Box(
        modifier = modifier
            .clip(shape)
    ) {
        val avatarProviderUrl = personIdent
            .email
            ?.let { email -> current.getAvatarUrl(email.sha256) }

        var isSuccessfulLoad by remember { mutableStateOf(false) }

        AsyncImage(
            model = avatarProviderUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize(),
            onState = {
                isSuccessfulLoad = it is AsyncImagePainter.State.Success
            }
        )

        if (avatarProviderUrl == null || !isSuccessfulLoad) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(fill),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = personIdent.name?.firstOrNull()?.uppercase() ?: "#",
                    color = Color.White,
                )
            }
        }
    }
}