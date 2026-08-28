/*
 * Copyright (c) 2025 Proton Technologies AG
 * This file is part of Proton Technologies AG and Proton Mail.
 *
 * Proton Mail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Proton Mail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Proton Mail. If not, see <https://www.gnu.org/licenses/>.
 */

package ch.protonmail.android.mailcomposer.presentation.usecase

import android.content.Context
import arrow.core.getOrElse
import androidx.core.net.toUri
import ch.protonmail.android.extidentities.domain.model.SmtpMessageAttachment
import ch.protonmail.android.mailattachments.domain.model.AttachmentId
import ch.protonmail.android.mailattachments.domain.repository.AttachmentRepository
import ch.protonmail.android.mailattachments.presentation.model.AttachmentMetadataUiModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import me.proton.core.domain.entity.UserId
import timber.log.Timber

/**
 * Reads the decrypted bytes of the composer's current attachments so they can be
 * attached to an external-SMTP message (and encrypted into the Proton sent copy).
 */
class GetExternalIdentityAttachments @Inject constructor(
    @ApplicationContext private val context: Context,
    private val attachmentRepository: AttachmentRepository
) {

    suspend operator fun invoke(
        userId: UserId,
        attachments: List<AttachmentMetadataUiModel>
    ): List<SmtpMessageAttachment> = attachments.mapNotNull { uiModel ->
        attachmentRepository.getAttachment(userId, AttachmentId(uiModel.id.value))
            .map { decrypted ->
                val bytes = context.contentResolver
                    .openInputStream(decrypted.fileUri)
                    ?.use { it.readBytes() }
                if (bytes == null) {
                    Timber.w("composer: failed to read attachment bytes for ${decrypted.fileName}")
                    null
                } else {
                    SmtpMessageAttachment(
                        fileName = decrypted.fileName,
                        mimeType = decrypted.metadata.mimeType.mime,
                        bytes = bytes
                    )
                }
            }
            .getOrElse {
                Timber.w("composer: failed to resolve attachment ${uiModel.id.value}")
                null
            }
    }
}