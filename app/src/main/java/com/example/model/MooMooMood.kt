package com.example.model

enum class MooMooModelTier(
    val id: String,
    val label: String,
    val modelName: String,
    val description: String
) {
    SMART(
        id = "smart",
        label = "Moo Moo 3.8",
        modelName = "gemini-3.8-flash",
        description = "Best for reasoning, writing, and code"
    ),
    FAST(
        id = "fast",
        label = "Moo Moo Fast",
        modelName = "gemini-3.5-flash-lite",
        description = "Ultra-fast everyday responses"
    );

    companion object {
        fun fromId(id: String): MooMooModelTier =
            entries.firstOrNull { it.id == id } ?: SMART
    }
}

enum class MooMooMood(
    val id: String,
    val displayName: String,
    val tagline: String,
    val systemPrompt: String
) {
    CLASSIC(
        id = "classic",
        displayName = "General Assistant",
        tagline = "Clear, intelligent, ChatGPT-style all-around helper",
        systemPrompt = """
            You are Moo Moo, a smart, articulate, and helpful AI assistant.
            Respond directly and thoughtfully like a world-class conversational AI:
            - Be clear, accurate, well-structured, and genuinely useful.
            - Use clean Markdown formatting (headings, bullet points, bold emphasis, and fenced code blocks with language tags) whenever it makes answers easier to scan.
            - Match the user's tone and depth: concise for quick questions, thorough and step-by-step for complex topics.
            - Stay natural and direct without repetitive catchphrases or filler.
        """.trimIndent()
    ),
    BRAINSTORM(
        id = "brainstorm",
        displayName = "Creative Writer",
        tagline = "Ideas, drafts, naming, and storytelling",
        systemPrompt = """
            You are Moo Moo in Creative Writer mode—an imaginative collaborator for brainstorming, copywriting, and storytelling.
            Offer original angles, crisp prose, vivid options, and structured variations tailored to the user's goal.
        """.trimIndent()
    ),
    STUDY(
        id = "study",
        displayName = "Step-by-Step Tutor",
        tagline = "Clear explanations and guided learning",
        systemPrompt = """
            You are Moo Moo in Step-by-Step Tutor mode—a patient, insightful teacher.
            Break down complex concepts from first principles, use intuitive analogies and examples, and summarize key takeaways clearly.
        """.trimIndent()
    ),
    CODING(
        id = "coding",
        displayName = "Code & Tech",
        tagline = "Clean code, architecture, and debugging",
        systemPrompt = """
            You are Moo Moo in Code & Tech mode—a pragmatic senior software engineer.
            Provide idiomatic, production-quality code in fenced blocks, explain root causes clearly, and highlight edge cases concisely.
        """.trimIndent()
    ),
    CUSTOM(
        id = "custom",
        displayName = "Custom Instructions",
        tagline = "Follows your personal system instructions",
        systemPrompt = """
            You are Moo Moo, a helpful AI assistant following the user's custom instructions closely while staying clear, accurate, and well-structured.
        """.trimIndent()
    );

    companion object {
        fun fromId(id: String): MooMooMood =
            entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}

data class StarterPrompt(
    val title: String,
    val subtitle: String,
    val prompt: String
)

val defaultStarterPrompts = listOf(
    StarterPrompt(
        title = "Explain clearly",
        subtitle = "Break down any complex topic simply",
        prompt = "Explain how large language models predict the next token in simple, intuitive terms with a quick example."
    ),
    StarterPrompt(
        title = "Write & polish",
        subtitle = "Draft emails, posts, or summaries",
        prompt = "Help me write a clear, polite follow-up email after a project kickoff meeting. Give me a short and a detailed version."
    ),
    StarterPrompt(
        title = "Code & debug",
        subtitle = "Write clean snippets or fix bugs",
        prompt = "Show me a clean Kotlin coroutine example that retries a failing network request with exponential backoff."
    ),
    StarterPrompt(
        title = "Brainstorm ideas",
        subtitle = "Fresh angles for projects or plans",
        prompt = "Brainstorm 5 practical weekend side-project ideas I can build and ship in 48 hours."
    )
)
