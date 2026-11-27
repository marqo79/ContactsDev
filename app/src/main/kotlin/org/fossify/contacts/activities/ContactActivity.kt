package org.fossify.contacts.activities

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.BaseTypes
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.google.android.material.appbar.MaterialToolbar
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.insetsController
import org.fossify.commons.extensions.launchSendSMSIntent
import org.fossify.commons.extensions.normalizePhoneNumber
import org.fossify.commons.extensions.sendEmailIntent
import org.fossify.commons.extensions.setNavigationBarAppearance
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.models.RadioItem
import org.fossify.commons.models.contacts.Contact
import org.fossify.contacts.R
import org.fossify.contacts.extensions.defaultPhoneNumber
import org.fossify.contacts.extensions.shareContacts
import java.util.Locale

abstract class ContactActivity : SimpleActivity() {
    companion object {
        protected const val PICK_RINGTONE_INTENT_ID = 1500
        protected const val INTENT_SELECT_RINGTONE = 600
    }

    protected var contact: Contact? = null
    protected var originalRingtone: String? = null
    protected var currentContactPhotoPath = ""
    private val phoneNumberRegion by lazy {
        val simCountry = runCatching { getSystemService(TelephonyManager::class.java)?.simCountryIso }.getOrNull()
        simCountry.orEmpty().ifBlank { Locale.getDefault().country }.uppercase(Locale.ROOT)
    }

    protected fun phoneNumberComparisonKey(value: String): String {
        if (phoneNumberRegion.isNotEmpty() && value.all { it.isDigit() || it.isWhitespace() || it in "+()./-" }) {
            PhoneNumberUtils.formatNumberToE164(value, phoneNumberRegion)?.let { return it }
        }
        return value.normalizePhoneNumber().ifEmpty { value }
    }

    override fun onResume() {
        super.onResume()
        window.insetsController().isAppearanceLightStatusBars = ColorUtils.calculateLuminance(getProperBackgroundColor()) > 0.5
        window.setNavigationBarAppearance(getProperBackgroundColor())
    }

    protected fun setupContactToolbarColors(toolbar: MaterialToolbar) {
        val textColor = getProperTextColor()
        toolbar.setNavigationIconTint(textColor)
        toolbar.overflowIcon = toolbar.overflowIcon?.mutate()?.apply { setTint(textColor) }
        val tint = ColorStateList.valueOf(textColor)
        for (index in 0 until toolbar.menu.size()) {
            toolbar.menu.getItem(index).iconTintList = tint
        }
    }

    protected fun contactCardBackground(strokeColor: Int = ColorUtils.setAlphaComponent(getProperTextColor(), 28)): GradientDrawable {
        val background = getProperBackgroundColor()
        val light = ColorUtils.calculateLuminance(background) > 0.5
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(ColorUtils.blendARGB(background, Color.WHITE, if (light) 0.72f else 0.08f))
            setStroke(density.toInt().coerceAtLeast(1), strokeColor)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == PICK_RINGTONE_INTENT_ID && resultCode == RESULT_OK && resultData != null && resultData.dataString != null) {
            customRingtoneSelected(Uri.decode(resultData.dataString!!))
        } else if (requestCode == INTENT_SELECT_RINGTONE && resultCode == Activity.RESULT_OK && resultData != null) {
            val extras = resultData.extras
            if (extras?.containsKey(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) == true) {
                val uri = extras.getParcelable<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                try {
                    systemRingtoneSelected(uri)
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            }
        }
    }

    abstract fun customRingtoneSelected(ringtonePath: String)

    abstract fun systemRingtoneSelected(uri: Uri?)

    protected fun getContactAvatarPlaceholder(): Drawable =
        SimpleContactsHelper(this).getContactLetterIcon(contact?.getNameToDisplay().orEmpty()).toDrawable(resources)

    fun showPhotoPlaceholder(photoView: ImageView) {
        photoView.setImageDrawable(getContactAvatarPlaceholder())
        currentContactPhotoPath = ""
        contact?.photo = null
    }

    fun updateContactPhoto(path: String, photoView: ImageView, bitmap: Bitmap? = null) {
        currentContactPhotoPath = path

        if (isDestroyed || isFinishing) {
            return
        }

        val options = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .centerCrop()

        val avatarSize = resources.getDimensionPixelSize(R.dimen.contact_header_avatar_size)

        Glide.with(this)
            .load(bitmap ?: path)
            .transition(DrawableTransitionOptions.withCrossFade())
            .apply(options)
            .override(avatarSize, avatarSize)
            .listener(object : RequestListener<Drawable> {
                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    return false
                }

                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                    showPhotoPlaceholder(photoView)
                    return true
                }
            }).into(photoView)
    }

    fun deleteContact() {
        ConfirmationDialog(this) {
            if (contact != null) {
                ContactsHelper(this).deleteContact(contact!!, false) {
                    finish()
                }
            }
        }
    }

    fun shareContact(contact: Contact) {
        shareContacts(arrayListOf(contact))
    }

    fun trySendSMS(recipient: Contact? = contact) {
        recipient?.defaultPhoneNumber()?.let { launchSendSMSIntent(it.value) }
    }

    fun trySendEmail() {
        val emails = contact!!.emails
        if (emails.size == 1) {
            sendEmailIntent(emails.first().value)
        } else if (emails.size > 1) {
            val items = ArrayList<RadioItem>()
            emails.forEachIndexed { index, email ->
                items.add(RadioItem(index, email.value, email.value))
            }

            RadioGroupDialog(this, items) {
                sendEmailIntent(it as String)
            }
        }
    }

    fun getEmailTypeText(type: Int, label: String): String {
        return if (type == BaseTypes.TYPE_CUSTOM) {
            label
        } else {
            getString(
                when (type) {
                    Email.TYPE_HOME -> org.fossify.commons.R.string.home
                    Email.TYPE_WORK -> org.fossify.commons.R.string.work
                    Email.TYPE_MOBILE -> org.fossify.commons.R.string.mobile
                    else -> org.fossify.commons.R.string.other
                }
            )
        }
    }

    fun getAddressTypeText(type: Int, label: String): String {
        return if (type == BaseTypes.TYPE_CUSTOM) {
            label
        } else {
            getString(
                when (type) {
                    StructuredPostal.TYPE_HOME -> org.fossify.commons.R.string.home
                    StructuredPostal.TYPE_WORK -> org.fossify.commons.R.string.work
                    else -> org.fossify.commons.R.string.other
                }
            )
        }
    }

    fun getIMTypeText(type: Int, label: String): String {
        return if (type == Im.PROTOCOL_CUSTOM) {
            label
        } else {
            getString(
                when (type) {
                    Im.PROTOCOL_AIM -> R.string.aim
                    Im.PROTOCOL_MSN -> R.string.windows_live
                    Im.PROTOCOL_YAHOO -> R.string.yahoo
                    Im.PROTOCOL_SKYPE -> R.string.skype
                    Im.PROTOCOL_QQ -> R.string.qq
                    Im.PROTOCOL_GOOGLE_TALK -> R.string.hangouts
                    Im.PROTOCOL_ICQ -> R.string.icq
                    else -> R.string.jabber
                }
            )
        }
    }

    fun getEventTextId(type: Int) = when (type) {
        Event.TYPE_ANNIVERSARY -> org.fossify.commons.R.string.anniversary
        Event.TYPE_BIRTHDAY -> org.fossify.commons.R.string.birthday
        else -> org.fossify.commons.R.string.other
    }

    protected fun getDefaultRingtoneUri() = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    protected fun getRingtonePickerIntent(): Intent {
        val defaultRingtoneUri = getDefaultRingtoneUri()
        val currentRingtoneUri = if (contact!!.ringtone != null && contact!!.ringtone!!.isNotEmpty()) {
            Uri.parse(contact!!.ringtone)
        } else if (contact!!.ringtone?.isNotEmpty() == false) {
            null
        } else {
            defaultRingtoneUri
        }

        return Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, defaultRingtoneUri)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentRingtoneUri)
        }
    }
}
