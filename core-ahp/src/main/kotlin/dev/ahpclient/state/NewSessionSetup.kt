package dev.ahpclient.state

import dev.ahpclient.ahp.ChatConfigKeys

/**
 * How the new-session page lays out what the host offers. The page reads like an empty chat: the choices about where the agent works
 * (host, folder, isolation, branch, the agent harness and any other setting the host offers) sit at the top as chips, and the choices
 * about how it works with you (mode and approvals) sit in the message box beside the custom agent and the model, as they do once the
 * session is running. Pure, so it is tested without a phone.
 */
object NewSessionSetup {
    /** The host's settings that belong with the message box rather than the setup chips: how the agent works, and how freely its tools run. */
    val CHAT_KEYS: Set<String> = setOf(ChatConfigKeys.MODE, ChatConfigKeys.AUTO_APPROVE, ChatConfigKeys.APPROVAL_MODE)

    /** Setting ids the page knows by name, so it can give them an icon and a fixed place. */
    const val ISOLATION = "isolation"
    const val BRANCH = "branch"

    fun isChatField(field: ConfigField): Boolean = field.id in CHAT_KEYS

    /** The settings shown as setup chips, isolation and branch first (where they are offered), then the rest in the host's order. */
    fun setupFields(form: SessionConfigForm?): List<ConfigField> {
        val fields = form?.fields.orEmpty().filterNot(::isChatField)
        val known = listOf(ISOLATION, BRANCH).mapNotNull { id -> fields.firstOrNull { it.id == id } }
        return known + fields.filter { it !in known }
    }

    /** The mode and approvals the host offers for a new session, mode first. */
    fun chatFields(form: SessionConfigForm?): List<ConfigField> =
        form?.fields.orEmpty().filter(::isChatField).sortedBy { if (it.id == ChatConfigKeys.MODE) 0 else 1 }

    /** What is chosen for [field]: the person's pick, else what the host holds. */
    fun selected(field: ConfigField, picks: Map<String, ConfigPick>): ConfigPick? = picks[field.id] ?: field.value

    /** The words a chip shows for [field]'s value: the option's label, the text of a value the host completed, or null when none is chosen. */
    fun valueLabel(field: ConfigField, picks: Map<String, ConfigPick>): String? {
        val pick = selected(field, picks) ?: return null
        return field.options.firstOrNull { it.pick == pick }?.label ?: (pick as? ConfigPick.Text)?.value?.take(MAX_LABEL)
    }

    /** What the mode chip in the message box says, such as "Interactive · Manual"; null when the host offers neither. */
    fun chatLabel(form: SessionConfigForm?, picks: Map<String, ConfigPick>): String? {
        val fields = chatFields(form)
        if (fields.isEmpty()) return null
        return fields.joinToString(" · ") { valueLabel(it, picks) ?: it.title }
    }

    /**
     * True when the approvals chosen let tool calls run without asking: the value the host calls `autoApprove` (or `allow-all` on a
     * host that names it `approvalMode`), or an on/off approvals setting turned on. Shown in the warning colour, so it is never a surprise.
     */
    fun allowsAll(form: SessionConfigForm?, picks: Map<String, ConfigPick>): Boolean =
        chatFields(form).any { field ->
            field.id != ChatConfigKeys.MODE && when (val pick = selected(field, picks)) {
                is ConfigPick.Flag -> pick.value
                is ConfigPick.Text -> pick.value == "autoApprove" || pick.value == "allow-all"
                null -> false
            }
        }

    /** The folder chosen when the page opens: the most recent one the host used, or none (the host chooses) when it has none. */
    fun defaultFolder(folders: List<String>): String? = folders.firstOrNull()

    /** The agent harness chosen when the page opens: the first the host lists, which is its own default. */
    fun defaultProvider(providers: List<String>): String? = providers.firstOrNull()

    private const val MAX_LABEL = 80
}
