package com.omni.aiassistant.device.tools.comm

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Patterns
import com.omni.aiassistant.core.agent.Tool
import com.omni.aiassistant.core.agent.ToolArgs
import com.omni.aiassistant.core.agent.ToolArgs.int
import com.omni.aiassistant.core.agent.ToolArgs.string
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSchema
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.device.capability.Capability
import com.omni.aiassistant.device.capability.CapabilityCenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ContactsSearchTool(
    private val context: Context,
    private val capabilities: CapabilityCenter,
) : Tool {

    override val spec = ToolSpec(
        name = "contacts_search",
        description = "Search contacts on this phone by name or phone number. " +
            "Returns contact id, display name and phone number. Use it when you only " +
            "know part of a name or number, before calling or messaging someone.",
        category = ToolCategory.COMMUNICATION,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "query" to ToolSchema.string("Name or phone number to search for"),
                "limit" to ToolSchema.integer(
                    description = "Maximum number of contacts to return",
                    minimum = 1,
                    maximum = 50,
                    default = 10,
                ),
            ),
            required = listOf("query"),
        ),
    )

    override suspend fun execute(arguments: JsonObject): ToolOutcome {
        val query = arguments.string("query")?.trim()
            ?: return ToolOutcome.Failure("Missing required argument 'query'")
        if (query.length > 200) {
            return ToolOutcome.Failure("Search query is too long")
        }
        val limit = ToolArgs.clamp(
            arguments.int("limit") ?: 10,
            1,
            50,
        )
        if (!capabilities.isGranted(Capability.CONTACTS)) {
            return ToolOutcome.NeedsPermission(Capability.CONTACTS.name, CONTACTS_LABEL)
        }

        return withContext<ToolOutcome>(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                )
                val selection =
                    "(${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?" +
                        " OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?)"
                val selectionArgs = arrayOf("%$query%", "%$query%")
                val sortOrder =
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"

                val contacts = mutableListOf<ContactRow>()
                val seenContactIds = HashSet<Long>()
                resolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder,
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    )
                    val nameIndex = cursor.getColumnIndexOrThrow(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    )
                    val numberIndex = cursor.getColumnIndexOrThrow(
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                    )
                    while (cursor.moveToNext()) {
                        if (contacts.size >= limit) break
                        if (cursor.isNull(idIndex)) continue
                        val contactId = cursor.getLong(idIndex)
                        if (!seenContactIds.add(contactId)) continue
                        contacts += ContactRow(
                            contactId = contactId,
                            name = if (cursor.isNull(nameIndex)) null else {
                                cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.truncateTo(200)
                            },
                            number = if (cursor.isNull(numberIndex)) null else {
                                cursor.getString(numberIndex)?.takeIf { it.isNotBlank() }?.truncateTo(64)
                            },
                        )
                    }
                }

                val data = buildJsonObject {
                    put("query", query)
                    put("count", contacts.size)
                    put("contacts", JsonArray(contacts.map { it.toJson() }))
                }
                val summary = when (contacts.size) {
                    0 -> "No contacts found"
                    1 -> "Found 1 contact"
                    else -> "Found ${contacts.size} contacts"
                }
                ToolOutcome.Success(summary, data)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (security: SecurityException) {
                ToolOutcome.NeedsPermission(Capability.CONTACTS.name, CONTACTS_LABEL)
            } catch (error: Exception) {
                safeFailure("Could not search contacts", error)
            }
        }
    }

    private data class ContactRow(
        val contactId: Long,
        val name: String?,
        val number: String?,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("contactId", contactId)
            put("name", name)
            put("number", number)
        }
    }
}

internal class ContactCreateTool(
    private val context: Context,
    private val capabilities: CapabilityCenter,
) : Tool {

    override val spec = ToolSpec(
        name = "contact_create",
        description = "Create a new contact on the phone. Requires a name and phone " +
            "number; email is optional. The contact is written through the system " +
            "contacts provider.",
        category = ToolCategory.COMMUNICATION,
        risk = ToolRisk.MEDIUM,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "name" to ToolSchema.string("Contact display name"),
                "phone" to ToolSchema.string("Phone number, with optional country code"),
                "email" to ToolSchema.string("Optional email address"),
            ),
            required = listOf("name", "phone"),
        ),
    )

    override suspend fun execute(arguments: JsonObject): ToolOutcome {
        val name = arguments.string("name")?.trim()
            ?: return ToolOutcome.Failure("Missing required argument 'name'")
        val phone = arguments.string("phone")?.trim()
            ?: return ToolOutcome.Failure("Missing required argument 'phone'")
        val email = arguments.string("email")?.trim()?.takeIf { it.isNotEmpty() }

        if (name.length > 200) {
            return ToolOutcome.Failure("Contact name is too long")
        }
        if (phone.length > 64 || phone.count { it.isDigit() } < 3) {
            return ToolOutcome.Failure("Enter a valid phone number with at least 3 digits")
        }
        val phoneCharactersValid = phone.all { character ->
            character.isDigit() || character == '+' || character.isWhitespace() ||
                character == '-' || character == '(' || character == ')' || character == '.'
        }
        if (!phoneCharactersValid) {
            return ToolOutcome.Failure("Enter a valid phone number with at least 3 digits")
        }
        if (email != null && (email.length > 254 || !Patterns.EMAIL_ADDRESS.matcher(email).matches())) {
            return ToolOutcome.Failure("Enter a valid email address or omit it")
        }
        if (!capabilities.isGranted(Capability.CONTACTS)) {
            return ToolOutcome.NeedsPermission(Capability.CONTACTS.name, CONTACTS_LABEL)
        }
        if (context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ToolOutcome.NeedsPermission(Capability.CONTACTS.name, CONTACTS_LABEL)
        }

        return withContext<ToolOutcome>(Dispatchers.IO) {
            try {
                val values = ContentValues().apply {
                    putNull(ContactsContract.RawContacts.ACCOUNT_TYPE)
                    putNull(ContactsContract.RawContacts.ACCOUNT_NAME)
                }
                val operations = ArrayList<ContentProviderOperation>(4)
                operations += ContentProviderOperation
                    .newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValues(values)
                    .build()
                operations += ContentProviderOperation
                    .newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                    )
                    .withValue(
                        ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                        name,
                    )
                    .build()
                operations += ContentProviderOperation
                    .newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    )
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                    .withValue(
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                    )
                    .build()
                if (email != null) {
                    operations += ContentProviderOperation
                        .newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                        )
                        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                        .withValue(
                            ContactsContract.CommonDataKinds.Email.TYPE,
                            ContactsContract.CommonDataKinds.Email.TYPE_WORK,
                        )
                        .build()
                }

                val resolver = context.contentResolver
                val results = resolver.applyBatch(ContactsContract.AUTHORITY, operations)
                val rawContactUri = results.firstOrNull()?.uri
                    ?: return@withContext ToolOutcome.Failure("Contacts provider rejected the insert")
                val rawContactId = ContentUris.parseId(rawContactUri)
                val contactId = resolver.query(
                    ContactsContract.RawContacts.CONTENT_URI,
                    arrayOf(ContactsContract.RawContacts.CONTACT_ID),
                    "${ContactsContract.RawContacts._ID} = ?",
                    arrayOf(rawContactId.toString()),
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
                }
                val displayName = contactId?.let { id ->
                    resolver.query(
                        ContactsContract.Contacts.CONTENT_URI,
                        arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                        "${ContactsContract.Contacts._ID} = ?",
                        arrayOf(id.toString()),
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) {
                            cursor.getString(0)?.takeIf { it.isNotBlank() }
                        } else {
                            null
                        }
                    }
                } ?: name

                val data = buildJsonObject {
                    put("displayName", displayName)
                    put("phone", phone)
                    put("contactId", contactId)
                    email?.let { put("email", it) }
                }
                ToolOutcome.Success("Contact created", data)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (security: SecurityException) {
                ToolOutcome.NeedsPermission(Capability.CONTACTS.name, CONTACTS_LABEL)
            } catch (error: Exception) {
                safeFailure("Could not create contact", error)
            }
        }
    }
}
