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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.unit.sp
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
    if (confirmDelete && onDelete != null) ConfirmDialog("Forget this note?", "I won't remember “$title” any more.", "Forget it", {
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
 * What Dash knows about you, on one page: what you told him (your instructions, every agent's at the start of every
 * task; a tap edits them), then what he's learned (Claude's memory, as Claude Code keeps it, see [MEMORY_DIR]: its
 * notes by title, each opening to read, change or forget). Droid adds nothing to how the notes are made.
 */
@Composable
fun KnowScreen(backend: Backend, onBack: () -> Unit, onInstructions: () -> Unit) {
    var open by remember { mutableStateOf<Note?>(null) }
    var version by remember { mutableIntStateOf(0) }  // reloads after a note was changed or forgotten
    var told by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf<List<Note>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(version) {
        try {
            withContext(Dispatchers.IO) {
                told = frontmatter(backend.read(Doc.INSTRUCTIONS)).second.trim()
                notes = backend.memory()
            }
        } catch (e: Exception) { error = e.message ?: "Could not read what I know" }
    }
    open?.let { n ->
        val done: () -> Unit = { open = null; version++ }
        return DocScreen(backend, n.doc, n.title, null, "note", done, onDelete = done)
    }
    SubScreen("What I know about you", onBack, backTag = "know_close") {
        LazyColumn(Modifier.fillMaxSize().testTag("know"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            item("told") {
                KitLabel("What you told me", Modifier.padding(top = 0.dp))
                Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.surface).clickable(onClick = onInstructions)
                    .padding(horizontal = 18.dp, vertical = 16.dp).testTag("instructions")) {
                    Text(told?.ifBlank { "Nothing yet. Tell me about yourself, the people and apps you use, and how you like things done." } ?: "…",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp), maxLines = 6, overflow = TextOverflow.Ellipsis)
                    Text("I read this before every task. Tap to change it.", Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = P.muted)
                }
            }
            item("learned") { KitLabel("What I've learned") }
            val list = notes
            when {
                error != null -> item("error") { Text(error!!, Modifier.padding(vertical = 16.dp), color = P.muted) }
                list == null -> item("loading") { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Busy(size = 24.dp) } }
                list.isEmpty() -> item("none") { Text("Nothing yet. I keep notes myself as I work, and they show up here.", Modifier.padding(start = 4.dp, top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = P.muted) }
                else -> itemsIndexed(list, key = { _, n -> n.doc.path }) { i, n ->
                    KitRow(n.title, Modifier.testTag("note"), sub = n.about.ifEmpty { null }, lead = { IconTile(R.drawable.ic_notes) }, line = i > 0,
                        onClick = { open = n }, trailing = { Chevron() })
                }
            }
        }
    }
}
