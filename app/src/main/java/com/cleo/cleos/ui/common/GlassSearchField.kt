package com.cleo.cleos.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette

/** The same capsule and input spacing wherever the app searches saved words. */
@Composable
fun GlassSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    description: String,
    fieldModifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = GlassShape.Capsule,
        contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = palette.contentSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (value.isEmpty()) Text(placeholder, color = palette.contentSecondary, fontSize = 16.sp)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(color = palette.content, fontSize = 16.sp),
                    cursorBrush = SolidColor(palette.accentContent),
                    modifier = fieldModifier.fillMaxWidth().semantics { contentDescription = description },
                )
            }
            if (value.isNotEmpty()) {
                Box(Modifier.size(34.dp).clip(CircleShape).clickable { onValueChange("") }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, contentDescription = "清空", tint = palette.contentSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
