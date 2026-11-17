package dev.tim.assistant.model

import kotlinx.serialization.Serializable

/**
 * One Gmail message as `GET /v1/mail/{gmailId}/body` returns it. [date] is Gmail's internalDate in epoch ms;
 * [text] is at most 20 000 characters (the text/plain part when there is one, else HTML with tags stripped).
 * No attachments, no thread fields.
 */
@Serializable
data class MailBody(val from: String, val to: String, val subject: String, val date: Long, val text: String)

/** A user label from `GET /v1/mail/labels` (Gmail type `user` only). */
@Serializable
data class MailLabel(val id: String, val name: String)
