package com.adetkov.droid

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Edits a text in Droid's Linux: the owner's instructions, or one of Claude's memory notes ([onDelete]: it can be
 * deleted). A frontmatter its writer keeps (a note's name and description) is left out of the editor and kept as is.
 */
@Composable
fun DocScreen(backend: Backend, doc: Doc, title: String, about: String?, tag: String, onClose: () -> Unit, onDelete: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptic()
    var head by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<String?>(null) }  // null while loading
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val dirty = saved != null && text != saved

    LaunchedEffect(Unit) {
        try { frontmatter(withContext(Dispatchers.IO) { backend.read(doc) }).let { (h, body) -> head = h; saved = body; text = body } }
        catch (e: Exception) { error = e.message ?: "Could not read ${doc.label}" }
    }
    fun close() = if (dirty) confirmDiscard = true else onClose()
    BackHandler { close() }

    if (confirmDiscard) ConfirmDialog("Discard changes?", null, "Discard", onClose, { confirmDiscard = false }, danger = true, dismiss = "Keep editing")
    if (confirmDelete && onDelete != null) ConfirmDialog("Delete this memory?", "Claude won't remember “$title” any more.", "Delete", {
        scope.launch {
            try { withContext(Dispatchers.IO) { backend.delete(doc) }; haptic(); onDelete() }
            catch (e: Exception) { Toast.makeText(ctx, e.message ?: "Could not delete", Toast.LENGTH_SHORT).show() }
        }
    }, { confirmDelete = false }, danger = true)

    SubScreen(title, ::close, backTag = "${tag}_close", actions = {
        if (onDelete != null && saved != null) IconBtn(R.drawable.ic_delete, "Delete", { confirmDelete = true }, Modifier.testTag("${tag}_delete"))
        Text(
            "Save",
            Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50)).background(if (dirty) P.call else P.raised)
                .clickable(enabled = dirty) {
                    val t = text
                    scope.launch {
                        try { withContext(Dispatchers.IO) { backend.write(doc, head + t) }; saved = t; haptic(); onClose() }
                        catch (e: Exception) { Toast.makeText(ctx, e.message ?: "Could not save", Toast.LENGTH_SHORT).show() }
                    }
                }
                .padding(horizontal = 16.dp, vertical = 8.dp).testTag("${tag}_save"),
            style = MaterialTheme.typography.labelLarge, color = if (dirty) OnCall else P.faint,
        )
    }) {
        Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopStart) {
            when {
                error != null -> Text(error!!, Modifier.padding(24.dp), color = P.muted)
                saved == null -> Spinner(Modifier.align(Alignment.Center), size = 24.dp)
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                    if (about != null) Text(about, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = P.muted)
                    Box(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(20.dp)).background(P.surface).padding(16.dp)) {
                        BasicTextField(
                            text, { text = it }, Modifier.fillMaxWidth().testTag("${tag}_text"),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = P.text), cursorBrush = SolidColor(P.text),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Claude's memory, as Claude Code keeps it (see [MEMORY_DIR]): its notes by title, each opening to read, change or
 * delete. Droid adds nothing to how the notes are made.
 */
@Composable
fun MemoryScreen(backend: Backend, onClose: () -> Unit) {
    var open by remember { mutableStateOf<Note?>(null) }
    var version by remember { mutableIntStateOf(0) }  // reloads the list after a note was changed or deleted
    var notes by remember { mutableStateOf<List<Note>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(version) {
        try { notes = withContext(Dispatchers.IO) { backend.memory() } }
        catch (e: Exception) { error = e.message ?: "Could not read Claude's memory" }
    }
    open?.let { n ->
        val done: () -> Unit = { open = null; version++ }
        return DocScreen(backend, n.doc, n.title, null, "note", done, onDelete = done)
    }
    BackHandler { onClose() }
    SubScreen("Memory", onClose, backTag = "memory_close") {
        LazyColumn(Modifier.fillMaxSize().testTag("memory"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text("What Claude has learned about you and your phone while working. Claude Code keeps these notes and " +
                    "decides what to remember; open one to change or delete it.",
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = P.muted)
            }
            val list = notes
            when {
                error != null -> item { Text(error!!, Modifier.padding(24.dp), color = P.muted) }
                list == null -> item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Spinner(size = 24.dp) } }
                list.isEmpty() -> item { Text("Nothing yet.", Modifier.padding(horizontal = 20.dp, vertical = 16.dp), style = MaterialTheme.typography.bodyMedium, color = P.faint) }
                else -> items(list, key = { it.doc.path }) { n ->
                    Column(Modifier.fillMaxWidth().clickable { open = n }.padding(horizontal = 20.dp, vertical = 12.dp).testTag("note")) {
                        Text(n.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (n.about.isNotEmpty()) Text(n.about, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall,
                            color = P.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
