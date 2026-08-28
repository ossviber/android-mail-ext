/*
 * Copyright (c) 2025 Proton Technologies AG
 * This file is part of Proton Technologies AG and Proton Mail.
 *
 * Proton Mail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation. Proton Mail is distributed in the
 * hope that it will be useful, but WITHOUT ANY WARRANTY; without even the
 * implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Proton Mail. If not, see <https://www.gnu.org/licenses/>.
 */

package ch.protonmail.android.extidentities.domain

/**
 * Automation that labels the sent e-mails an external identity stores in Proton:
 * the sent copy is imported straight into the built-in Sent label and, when the
 * automation is on, additionally tagged with a per-identity label so mail from
 * different external identities can be told apart.
 */

/**
 * Display name of the Proton label collecting the identity's sent copies:
 * `<localized Sent> (<email>)`. [base] is the localized word for "Sent" (see
 * `ext_identities_automation_folder_base`).
 */
fun sentLabelNameFor(base: String, email: String): String = "$base ($email)"

private const val DQUOTE = 34.toChar()

private fun q(value: String): String = DQUOTE + value + DQUOTE

/**
 * Builds the Sieve rule filing incoming hidden copies into the identity's label.
 *
 * A message is filed when it comes from the external address AND none of the given
 * Proton addresses appears in To/Cc - i.e. the account was only a hidden BCC
 * recipient. Mail genuinely addressed to the Proton account stays in the inbox.
 */
fun buildSentCopySieveRule(
    externalAddress: String,
    labelName: String,
    protonAddresses: List<String>
): String {
    val protectedList = protonAddresses
        .distinct()
        .joinToString(separator = ", ") { address -> q(address) }
    return buildString {
        appendLine("require [" + q("fileinto") + "];")
        appendLine()
        appendLine("# >>> Proton Mail Enhanced - sent copies for $externalAddress")
        appendLine("if allof (")
        appendLine("    address :is " + q("from") + " " + q(externalAddress) + ",")
        appendLine("    not address :is [" + q("to") + ", " + q("cc") + "] [$protectedList]")
        appendLine(") {")
        appendLine("    fileinto " + q(labelName) + ";")
        appendLine("}")
        append("# <<< Proton Mail Enhanced")
    }
}