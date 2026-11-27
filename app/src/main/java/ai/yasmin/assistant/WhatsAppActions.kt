package ai.yasmin.assistant

import kotlinx.coroutines.CancellationException
import java.util.Locale

/*
 * WhatsApp action engine (step 5).
 *
 * An action is a small closed unit of work on ONE incoming WhatsApp message. Three exist and nothing else can be run:
 *
 *   IDENTIFY_MESSAGE_TYPE   what kind of message is it (text, photo, voice message, ...)           real, no AI
 *   REPLY_TO_INCOMING       prepare a reply DRAFT for this message                                  real, uses the AI
 *   CONTINUE_CONVERSATION   the same, for a chat that already has earlier saved messages: the       real, uses the AI
 *                           draft takes that chat's own history into account
 *
 * A draft is text kept in memory. Nothing in this step sends it, taps a button, opens WhatsApp or touches the screen, and
 * no action ever reports success for something that did not happen: a result is either what was really done
 * ([WhatsAppActionResult.TypeIdentified], [WhatsAppActionResult.DraftPrepared]), a deliberate "nothing to do"
 * ([WhatsAppActionResult.NoAction]) or a failure. AI output is untrusted text; it is cleaned and stored, never executed,
 * and there is no code path from it to a shell, a command or an Android action.
 */

enum class WhatsAppActionType { IDENTIFY_MESSAGE_TYPE, REPLY_TO_INCOMING, CONTINUE_CONVERSATION }

enum class ActionSupport {
    /** Works for real and completes by itself. */
    IMPLEMENTED,
    /** Works up to a prepared draft; nothing is sent or done in the app. */
    DRAFT_ONLY,
    /** Does not exist in this build. */
    NOT_IMPLEMENTED
}

class WhatsAppCapability(val name: String, val support: ActionSupport, val note: String)

/**
 * The honest list of what the WhatsApp agent can and cannot do in THIS build. Separate from WhatsAppKnowledge on purpose:
 * knowing that WhatsApp has a feature says nothing about whether Android can automate it.
 */
object WhatsAppCapabilities {
    val ALL: List<WhatsAppCapability> = listOf(
        WhatsAppCapability("Identify the message type", ActionSupport.IMPLEMENTED,
            "From the notification text only (English words and usual symbols); other languages look like text."),
        WhatsAppCapability("Prepare a reply draft", ActionSupport.DRAFT_ONLY,
            "Text messages only. The draft stays in memory; nothing is sent."),
        WhatsAppCapability("Continue an existing conversation", ActionSupport.DRAFT_ONLY,
            "A draft that uses that chat's own saved messages, prepared only because a new message arrived."),
        WhatsAppCapability("Send a prepared reply", ActionSupport.IMPLEMENTED,
            "Step 10, only through the Automatic replies switch: the notification's own Reply button, after the owner check, and only counted as sent when the app shows the message. " +
                "Chats whose notification has no Reply button are not answered."),
        WhatsAppCapability("Automatic replies / waiting time", ActionSupport.IMPLEMENTED, "Off by default (Settings). Private chats only, newest message only, at most 20 per person, then one closing message."),
        WhatsAppCapability("Start a conversation or message anyone unprompted", ActionSupport.NOT_IMPLEMENTED, "Deliberately not offered."),
        WhatsAppCapability("Understand photos, videos, voice messages or files", ActionSupport.NOT_IMPLEMENTED, "Only a placeholder text is visible."),
        WhatsAppCapability("Send media, react, mark as read, call", ActionSupport.NOT_IMPLEMENTED, "No such action exists.")
    )

    fun support(type: WhatsAppActionType): ActionSupport = when (type) {
        WhatsAppActionType.IDENTIFY_MESSAGE_TYPE -> ActionSupport.IMPLEMENTED
        WhatsAppActionType.REPLY_TO_INCOMING, WhatsAppActionType.CONTINUE_CONVERSATION -> ActionSupport.DRAFT_ONLY
    }
}

/** Everything one action may look at. It describes ONE conversation: there is no way to reach another one from here. */
class WhatsAppActionEnv(
    val message: ProcessedMessage,
    val info: WaMessageInfo,
    /** Earlier saved messages of THIS conversation, oldest first. Never contains [message] or anything newer. */
    val history: List<StoredMessage>,
    /** The Main Brain (the only writer of reply text; the agent has no AI access of its own). */
    val responses: BrainReplyPort,
    /** The user's switch "write reply drafts with Gemini". Off = nothing is sent to the AI. */
    val draftsEnabled: Boolean,
    /** false = a newer text message of this chat arrived while the draft was being written: the draft is dropped. */
    val stillRelevant: (suspend () -> Boolean)?,
    val now: Long,
    /** v2.0: first reply to this person: the draft starts with the introduction of Yasmin as Mehdi's AI assistant. */
    val introduce: Boolean = false
)

sealed class WhatsAppActionResult {
    class TypeIdentified(val info: WaMessageInfo) : WhatsAppActionResult()
    class DraftPrepared(val draft: WhatsAppDraft) : WhatsAppActionResult()
    /** Nothing to do, by design. [code] is a short code such as "unsupported_image" or "no_reply_needed". */
    class NoAction(val code: String) : WhatsAppActionResult()
    class Failed(val code: String, val retryable: Boolean) : WhatsAppActionResult()
}

interface WhatsAppAction {
    val type: WhatsAppActionType
    suspend fun execute(env: WhatsAppActionEnv): WhatsAppActionResult
}

class IdentifyMessageTypeAction : WhatsAppAction {
    override val type = WhatsAppActionType.IDENTIFY_MESSAGE_TYPE
    override suspend fun execute(env: WhatsAppActionEnv): WhatsAppActionResult = WhatsAppActionResult.TypeIdentified(env.info)
}

class ReplyToIncomingAction : WhatsAppAction {
    override val type = WhatsAppActionType.REPLY_TO_INCOMING
    override suspend fun execute(env: WhatsAppActionEnv): WhatsAppActionResult = WhatsAppDrafter.draft(env, type)
}

class ContinueConversationAction : WhatsAppAction {
    override val type = WhatsAppActionType.CONTINUE_CONVERSATION
    override suspend fun execute(env: WhatsAppActionEnv): WhatsAppActionResult = WhatsAppDrafter.draft(env, type)
}

object WhatsAppActionEngine {
    /** Runs one action. Never throws except for cancellation; any other problem becomes a failure that carries no message text. */
    suspend fun run(action: WhatsAppAction, env: WhatsAppActionEnv): WhatsAppActionResult =
        try {
            action.execute(env)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            WhatsAppActionResult.Failed("action_error_" + e.javaClass.simpleName.take(40), false)   // class name only: messages may contain chat text
        }
}

/**
 * The WhatsApp side of reply drafting. The text itself is written by the MAIN BRAIN ([BrainReplyPort] -> MainBrainReplyWriter: its own model,
 * same general rules, same prompt layout and limits as for Messenger). What stays here is only what belongs to WhatsApp: the
 * notes for the AI ([APP_INSTRUCTIONS]), the knowledge of which saved lines are placeholders ([WhatsAppKnowledge]) and the
 * translation of the engine's result into a WhatsApp action result. Nothing is sent.
 */
internal object WhatsAppDrafter {
    const val APP_INSTRUCTIONS =
        "This is a WhatsApp chat (private chat or group; WhatsApp or WhatsApp Business). " + WhatsAppKnowledge.AI_VIEW_LIMITS +
            " In a group chat answer only what the new message itself asks."

    suspend fun draft(env: WhatsAppActionEnv, purpose: WhatsAppActionType): WhatsAppActionResult {
        // v2.2: a voice message / audio file cannot be heard, so it gets the fixed apology in the conversation's language (no AI call, nothing invented about the audio).
        if (env.info.type == WaMessageType.VOICE_NOTE || env.info.type == WaMessageType.AUDIO) {
            if (env.stillRelevant?.invoke() == false) return WhatsAppActionResult.NoAction(ResponseCodes.NO_LONGER_RELEVANT)
            val text = BrainLanguage.voiceReply(env.history, { WhatsAppKnowledge.classify(it) != WaMessageType.TEXT }, env.introduce)
            return WhatsAppActionResult.DraftPrepared(WhatsAppDraft(env.message.conversationId, env.message.messageId, text, purpose, System.currentTimeMillis()))
        }
        env.info.draftBlocker?.let { return WhatsAppActionResult.NoAction(it) }
        if (!env.draftsEnabled) return WhatsAppActionResult.NoAction("ai_disabled")
        val response = env.responses.generate(
            ResponseRequest(
                platform = MessageSource.WHATSAPP,
                conversationId = env.message.conversationId,
                message = env.message,
                history = env.history,
                appInstructions = APP_INSTRUCTIONS,
                describeNonText = { raw -> WhatsAppKnowledge.classify(raw).let { if (it == WaMessageType.TEXT) null else it.label } },
                continuing = purpose == WhatsAppActionType.CONTINUE_CONVERSATION,
                stillRelevant = env.stillRelevant,
                introduce = env.introduce
            )
        )
        return when (response.status) {
            ResponseStatus.GENERATED -> {
                val text = response.text
                if (text.isNullOrEmpty()) WhatsAppActionResult.Failed(ResponseCodes.EMPTY_RESPONSE, false)
                else WhatsAppActionResult.DraftPrepared(WhatsAppDraft(response.conversationId, response.forMessageId, text, purpose, response.generatedAt))
            }
            ResponseStatus.NO_REPLY_NEEDED, ResponseStatus.SKIPPED, ResponseStatus.DISCARDED ->
                WhatsAppActionResult.NoAction(response.note ?: response.status.name.lowercase(Locale.ROOT))
            ResponseStatus.FAILED ->
                WhatsAppActionResult.Failed(response.error?.code ?: "ai_unknown", response.error?.retryable ?: false)
        }
    }
}
