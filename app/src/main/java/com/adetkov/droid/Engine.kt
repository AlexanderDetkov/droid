package com.adetkov.droid

/** A model choice: [alias] is passed to the engine as `--model` ("" lets the engine choose). */
class Model(val alias: String, val label: String, val detail: String)

/**
 * An agent engine Droid can run. Every engine runs inside phone-agent and speaks Claude Code's stream-json to the app
 * (Codex and OpenCode through the droid-engine adapter), so the rest of the app doesn't care which one it is.
 * [sessionId] is what the engine's session ids look like; [custom]: the model is free text (provider/model names change
 * too often to list), with [models] as shortcuts.
 */
enum class Engine(val id: String, val label: String, val models: List<Model>, val sessionId: Regex, val custom: Boolean = false) {
    CLAUDE("claude", "Claude Code", listOf(
        Model("", "Default", "Your plan's default model"),
        Model("opus", "Opus", "Most capable, for complex tasks"),
        Model("sonnet", "Sonnet", "Fast and capable"),
        Model("haiku", "Haiku", "Fastest, for quick tasks"),
    ), Regex("[0-9a-fA-F-]{36}")),
    CODEX("codex", "Codex", listOf(Model("", "Default", "The model Codex picks for your account")),
        Regex("[0-9a-fA-F-]{36}"), custom = true),
    OPENCODE("opencode", "OpenCode", listOf(Model("", "Default", "A free model that sees images; pick your own with Other…")),
        Regex("ses_[A-Za-z0-9]{10,40}"), custom = true);  // its default is picked each run (droid-engine)

    /** The model entry for [alias]; an unlisted alias of a [custom] engine is shown as itself. */
    fun model(alias: String): Model = models.firstOrNull { it.alias == alias } ?: if (custom && alias.matches(MODEL_NAME)) Model(alias, alias, "Custom model") else models[0]

    fun validModel(alias: String) = alias.isEmpty() || models.any { it.alias == alias } || (custom && alias.matches(MODEL_NAME))

    companion object {
        /** Model ids as engines write them: `opus`, `gpt-5.1-codex`, `anthropic/claude-sonnet-4-5`, `ollama/qwen3:30b`. */
        val MODEL_NAME = Regex("[A-Za-z0-9._:/@+-]{1,80}")
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: CLAUDE
    }
}
