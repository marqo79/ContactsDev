package org.fossify.contacts.activities

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.view.WindowManager
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.FitCenter
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import org.fossify.commons.dialogs.CallConfirmationDialog
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.SelectAlarmSoundDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.contacts.*
import org.fossify.contacts.R
import org.fossify.contacts.databinding.*
import org.fossify.contacts.dialogs.ChooseSocialDialog
import org.fossify.contacts.dialogs.ManageVisibleFieldsDialog
import org.fossify.contacts.extensions.*
import org.fossify.contacts.helpers.*
import java.util.Locale

class ViewContactActivity : ContactActivity() {
    private var isViewIntent = false
    private var wasEditLaunched = false
    private var duplicateContacts = ArrayList<Contact>()
    private var contactSources = ArrayList<ContactSource>()
    private var showFields = 0
    private var fullContact: Contact? = null    // contact with all fields filled from duplicates
    private var duplicateInitialized = false
    private var blockingRequest = 0
    private var updatingBlockedNumbers = false
    private val mergeDuplicate: Boolean get() = config.mergeDuplicateContacts
    private val binding by viewBinding(ActivityViewContactBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        if (checkAppSideloading()) {
            return
        }

        setupEdgeToEdge(padBottomSystem = listOf(binding.contactScrollview))

        showFields = config.showContactFields
        setupMenu()
    }

    override fun onResume() {
        super.onResume()
        isViewIntent = intent.action == ContactsContract.QuickContact.ACTION_QUICK_CONTACT || intent.action == Intent.ACTION_VIEW
        if (isViewIntent) {
            handlePermission(PERMISSION_READ_CONTACTS) {
                if (it) {
                    ensureBackgroundThread {
                        initContact()
                    }
                } else {
                    toast(org.fossify.commons.R.string.no_contacts_permission)
                    finish()
                }
            }
        } else {
            ensureBackgroundThread {
                initContact()
            }
        }
    }

    override fun onBackPressedCompat(): Boolean {
        return if (binding.contactPhotoBig.alpha == 1f) {
            hideBigContactPhoto()
            true
        } else {
            false
        }
    }

    private fun setupMenu() {
        setupContactToolbarColors(binding.contactToolbar)
        binding.contactToolbar.menu.apply {
            findItem(R.id.share).setOnMenuItemClickListener {
                if (fullContact != null) {
                    shareContact(fullContact!!)
                }
                true
            }

            findItem(R.id.edit).setOnMenuItemClickListener {
                if (contact != null) {
                    launchEditContact(contact!!, false)
                }
                true
            }

            findItem(R.id.open_with).setOnMenuItemClickListener {
                openWith()
                true
            }

            findItem(R.id.delete).setOnMenuItemClickListener {
                deleteContactWithMergeLogic()
                true
            }

            findItem(R.id.block_contact_numbers).setOnMenuItemClickListener {
                confirmContactBlocking(block = true)
                true
            }

            findItem(R.id.unblock_contact_numbers).setOnMenuItemClickListener {
                confirmContactBlocking(block = false)
                true
            }

            findItem(R.id.manage_visible_fields).setOnMenuItemClickListener {
                ManageVisibleFieldsDialog(this@ViewContactActivity) {
                    showFields = config.showContactFields
                    ensureBackgroundThread {
                        initContact()
                    }
                }
                true
            }
        }

        binding.contactToolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun initContact() {
        var wasLookupKeyUsed = false
        var contactId: Int
        try {
            contactId = intent.getIntExtra(CONTACT_ID, 0)
        } catch (e: Exception) {
            return
        }

        if (contactId == 0 && isViewIntent) {
            val data = intent.data
            if (data != null) {
                val rawId = if (data.path!!.contains("lookup")) {
                    val lookupKey = getLookupKeyFromUri(data)
                    if (lookupKey != null) {
                        contact = ContactsHelper(this).getContactWithLookupKey(lookupKey)
                        fullContact = contact?.copy()
                        wasLookupKeyUsed = true
                    }

                    getLookupUriRawId(data)
                } else {
                    getContactUriRawId(data)
                }

                if (rawId != -1) {
                    contactId = rawId
                }
            }
        }

        if (contactId != 0 && !wasLookupKeyUsed) {
            contact = ContactsHelper(this).getContactWithId(contactId, intent.getBooleanExtra(IS_PRIVATE, false))
            fullContact = contact?.copy()

            if (contact == null) {
                if (!wasEditLaunched) {
                    toast(org.fossify.commons.R.string.unknown_error_occurred)
                }
                finish()
            } else {
                runOnUiThread {
                    gotContact()
                }
            }
        } else {
            if (contact == null) {
                finish()
            } else {
                runOnUiThread {
                    gotContact()
                }
            }
        }
    }

    private fun gotContact() {
        if (isDestroyed || isFinishing) {
            return
        }

        binding.contactScrollview.beVisible()
        setupViewContact()
        binding.contactSendSms.beVisibleIf(contact!!.phoneNumbers.isNotEmpty())
        binding.contactStartCall.beVisibleIf(contact!!.phoneNumbers.isNotEmpty())
        binding.contactSendEmail.beVisibleIf(contact!!.emails.isNotEmpty())

        val currentContact = contact!!
        val avatar = getContactAvatarPlaceholder()
        val photo = currentContact.photo ?: currentContact.photoUri.takeIf { it.isNotEmpty() }
        if (photo == null) {
            Glide.with(this).clear(binding.contactPhoto)
            binding.contactPhoto.setImageDrawable(avatar)
            binding.contactPhoto.setOnClickListener(null)
            binding.contactPhoto.isClickable = false
            binding.contactPhoto.contentDescription = null
        } else {
            Glide.with(this).load(photo).placeholder(avatar).error(avatar).centerCrop().into(binding.contactPhoto)
            val options = RequestOptions()
                .transform(FitCenter(), RoundedCorners(resources.getDimension(org.fossify.commons.R.dimen.normal_margin).toInt()))

            Glide.with(this)
                .load(photo)
                .apply(options)
                .into(binding.contactPhotoBig)

            binding.contactPhoto.setOnClickListener {
                binding.contactPhotoBig.alpha = 0f
                binding.contactPhotoBig.beVisible()
                binding.contactPhotoBig.animate().alpha(1f).start()
            }

            binding.contactPhoto.contentDescription = getString(R.string.view_contact_photo)
            binding.contactPhotoBig.setOnClickListener {
                hideBigContactPhoto()
            }
        }

        val textColor = getProperTextColor()
        arrayOf(
            binding.contactEmailsImage, binding.contactAddressesImage, binding.contactImsImage,
            binding.contactEventsImage, binding.contactNotesImage, binding.contactRingtoneImage, binding.contactOrganizationImage,
            binding.contactWebsitesImage, binding.contactGroupsImage, binding.contactStartCall, binding.contactSendSms,
            binding.contactSendEmail, binding.contactToggleFavorite
        ).forEach {
            it.applyColorFilter(textColor)
        }

        binding.contactSendSms.setOnClickListener { trySendSMS(fullContact) }
        binding.contactStartCall.setOnClickListener { fullContact?.let { callContact(it) } }
        binding.contactSendEmail.setOnClickListener { trySendEmail() }

        binding.contactSendSms.setOnLongClickListener { toast(org.fossify.commons.R.string.send_sms); true; }
        binding.contactStartCall.setOnLongClickListener { toast(org.fossify.commons.R.string.call_contact); true; }
        binding.contactSendEmail.setOnLongClickListener { toast(org.fossify.commons.R.string.send_email); true; }

        updateTextColors(binding.contactScrollview)
        binding.contactToolbar.menu.findItem(R.id.open_with).isVisible = contact?.isPrivate() == false
    }

    private fun setupViewContact() {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        setupFavorite()
        setupNames()

        ContactsHelper(this).getContactSources {
            contactSources = it
            runOnUiThread {
                setupContactDetails()
            }
        }

        initializeDuplicateContacts {
            duplicateInitialized = true
            setupContactDetails()
        }
    }

    private fun setupContactDetails() {
        if (isFinishing || isDestroyed || contact == null) {
            return
        }

        setupPhoneNumbers()
        refreshContactBlocking()
        setupEmails()
        setupAddresses()
        setupIMs()
        setupEvents()
        setupWebsites()
        setupGroups()
        setupContactSources()
        setupNotes()
        setupRingtone()
        setupOrganization()
        updateTextColors(binding.contactScrollview)
    }

    private fun contactNumbersForBlocking() =
        fullContact?.phoneNumbers.orEmpty().map { it.value.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun requestBlockedNumbers(method: String, numbers: List<String>): Int? {
        val dialer = telecomManager.defaultDialerPackage
        if (dialer != "org.fossify.phone" && dialer != "org.fossify.phone.dev") {
            return null
        }

        return try {
            val extras = Bundle().apply { putStringArrayList("numbers", ArrayList(numbers)) }
            contentResolver.call(Uri.parse("content://$dialer.contactblocking"), method, null, extras)?.getInt("blocked")
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun refreshContactBlocking() {
        if (updatingBlockedNumbers) return
        val numbers = contactNumbersForBlocking()
        val request = ++blockingRequest
        if (numbers.isEmpty()) {
            showContactBlocking(0, 0)
            return
        }

        ensureBackgroundThread {
            val blocked = requestBlockedNumbers("status", numbers)
            runOnUiThread {
                if (!isFinishing && !isDestroyed && request == blockingRequest) {
                    showContactBlocking(numbers.size, blocked)
                }
            }
        }
    }

    private fun showContactBlocking(total: Int, blocked: Int?) {
        val count = blocked ?: 0
        binding.contactToolbar.menu.findItem(R.id.block_contact_numbers).isVisible = count < total
        binding.contactToolbar.menu.findItem(R.id.unblock_contact_numbers).isVisible = count > 0
        binding.contactBlockingStatus.apply {
            isVisible = count > 0
            setText(if (count == total) R.string.contact_numbers_blocked else R.string.contact_numbers_partly_blocked)
            setTextColor(getProperTextColor())
        }
    }

    private fun confirmContactBlocking(block: Boolean) {
        val numbers = contactNumbersForBlocking()
        if (numbers.isEmpty() || updatingBlockedNumbers) return
        val message = if (block) R.string.block_contact_numbers_confirmation else R.string.unblock_contact_numbers_confirmation
        ConfirmationDialog(this, getString(message)) {
            updatingBlockedNumbers = true
            blockingRequest++
            ensureBackgroundThread {
                val blocked = requestBlockedNumbers(if (block) "block" else "unblock", numbers)
                runOnUiThread {
                    updatingBlockedNumbers = false
                    if (!isFinishing && !isDestroyed) {
                        when {
                            blocked == null -> toast(R.string.contact_blocking_unavailable)
                            blocked != (if (block) numbers.size else 0) -> toast(R.string.contact_blocking_failed)
                        }
                        refreshContactBlocking()
                    }
                }
            }
        }
    }

    private fun launchEditContact(contact: Contact, editExactContact: Boolean) {
        wasEditLaunched = true
        duplicateInitialized = false
        if (editExactContact) {
            editContact(contact)
        } else {
            editContact(contact, config.mergeDuplicateContacts)
        }
    }

    private fun openWith() {
        if (contact != null) {
            val uri = getContactPublicUri(contact!!)
            launchViewContactIntent(uri)
        }
    }

    private fun setupFavorite() {
        binding.contactToggleFavorite.apply {
            beVisible()
            tag = contact!!.starred
            setImageDrawable(getStarDrawable(tag == 1))

            setOnClickListener {
                val newIsStarred = if (tag == 1) 0 else 1
                ensureBackgroundThread {
                    val contacts = arrayListOf(contact!!)
                    if (newIsStarred == 1) {
                        ContactsHelper(context).addFavorites(contacts)
                    } else {
                        ContactsHelper(context).removeFavorites(contacts)
                    }
                }
                contact!!.starred = newIsStarred
                tag = contact!!.starred
                setImageDrawable(getStarDrawable(tag == 1))
            }

            setOnLongClickListener { toast(R.string.toggle_favorite); true; }
        }
    }

    private fun setupNames() {
        var displayName = contact!!.getNameToDisplay()
        if (contact!!.nickname.isNotEmpty()) {
            displayName += " (${contact!!.nickname})"
        }

        val showNameFields = showFields and SHOW_PREFIX_FIELD != 0 || showFields and SHOW_FIRST_NAME_FIELD != 0 || showFields and SHOW_MIDDLE_NAME_FIELD != 0 ||
            showFields and SHOW_SURNAME_FIELD != 0 || showFields and SHOW_SUFFIX_FIELD != 0

        binding.contactName.text = displayName
        binding.contactName.copyOnLongClick(displayName)
        binding.contactName.beVisibleIf(displayName.isNotEmpty() && !contact!!.isABusinessContact() && showNameFields)
    }

    private fun setupPhoneNumbers() {
        val ownNumbers = contact!!.phoneNumbers
        val linkedNumbers = duplicateContacts.flatMap { it.phoneNumbers }
        val numbersToShow = if (mergeDuplicate) ownNumbers + linkedNumbers else ownNumbers
        val defaultCandidates = if (duplicateInitialized) ownNumbers + linkedNumbers else ownNumbers
        val defaultKey = defaultCandidates.filter { it.isPrimary }
            .map { phoneNumberComparisonKey(it.value) }.distinct().singleOrNull()
        val visibleNumbers = numbersToShow.distinctBy { phoneNumberComparisonKey(it.value) }
            .map { it.copy(isPrimary = phoneNumberComparisonKey(it.value) == defaultKey) }
        fullContact!!.phoneNumbers = ArrayList(visibleNumbers)
        val defaultNumber = fullContact!!.defaultPhoneNumber()
        binding.contactNumbersHolder.removeAllViews()

        if (visibleNumbers.isNotEmpty() && showFields and SHOW_PHONE_NUMBERS_FIELD != 0) {
            visibleNumbers.forEach { phoneNumber ->
                ItemViewPhoneNumberBinding.inflate(layoutInflater, binding.contactNumbersHolder, false).apply {
                    binding.contactNumbersHolder.addView(root)
                    contactNumber.text = phoneNumber.value
                    val numberType = getPhoneNumberTypeText(phoneNumber.type, phoneNumber.label)
                    contactNumberType.text = if (phoneNumber === defaultNumber) {
                        getString(R.string.contact_default_number_type, numberType)
                    } else {
                        numberType
                    }
                    root.copyOnLongClick(phoneNumber.value)

                    root.background = contactCardBackground()
                    callNumber.applyColorFilter(getProperTextColor())
                    messageNumber.applyColorFilter(getProperTextColor())
                    callNumber.setOnClickListener {
                        if (config.showCallConfirmation) {
                            CallConfirmationDialog(this@ViewContactActivity, phoneNumber.value) {
                                startCallIntent(phoneNumber.value)
                            }
                        } else {
                            startCallIntent(phoneNumber.value)
                        }
                    }
                    messageNumber.setOnClickListener { launchSendSMSIntent(phoneNumber.value) }

                }
            }
            binding.contactNumbersHolder.beVisible()
        } else {
            binding.contactNumbersHolder.beGone()
        }

        // make sure the Call and SMS buttons are visible if any phone number is shown
        if (visibleNumbers.isNotEmpty()) {
            binding.contactSendSms.beVisible()
            binding.contactStartCall.beVisible()
        }
    }

    // a contact cannot have different emails per contact source. Such contacts are handled as separate ones, not duplicates of each other
    private fun setupEmails() {
        binding.contactEmailsHolder.removeAllViews()
        val emails = contact!!.emails
        if (emails.isNotEmpty() && showFields and SHOW_EMAILS_FIELD != 0) {
            emails.forEach {
                ItemViewEmailBinding.inflate(layoutInflater, binding.contactEmailsHolder, false).apply {
                    val email = it
                    binding.contactEmailsHolder.addView(root)
                    contactEmail.text = email.value
                    contactEmailType.text = getEmailTypeText(email.type, email.label)
                    root.copyOnLongClick(email.value)

                    root.setOnClickListener {
                        sendEmailIntent(email.value)
                    }
                }
            }
            binding.contactEmailsImage.beVisible()
            binding.contactEmailsHolder.beVisible()
        } else {
            binding.contactEmailsImage.beGone()
            binding.contactEmailsHolder.beGone()
        }
    }

    private fun setupAddresses() {
        var addresses = contact!!.addresses.toMutableSet() as LinkedHashSet<Address>

        if (mergeDuplicate) {
            duplicateContacts.forEach {
                addresses.addAll(it.addresses)
            }
        }

        addresses = addresses.sortedBy { it.type }.toMutableSet() as LinkedHashSet<Address>
        fullContact!!.addresses = addresses.toMutableList() as ArrayList<Address>
        binding.contactAddressesHolder.removeAllViews()

        if (addresses.isNotEmpty() && showFields and SHOW_ADDRESSES_FIELD != 0) {
            addresses.forEach {
                ItemViewAddressBinding.inflate(layoutInflater, binding.contactAddressesHolder, false).apply {
                    val address = it
                    binding.contactAddressesHolder.addView(root)
                    contactAddress.text = address.value
                    contactAddressType.text = getAddressTypeText(address.type, address.label)
                    root.copyOnLongClick(address.value)

                    root.setOnClickListener {
                        sendAddressIntent(address.value)
                    }
                }
            }
            binding.contactAddressesImage.beVisible()
            binding.contactAddressesHolder.beVisible()
        } else {
            binding.contactAddressesImage.beGone()
            binding.contactAddressesHolder.beGone()
        }
    }

    private fun setupIMs() {
        var IMs = contact!!.IMs.toMutableSet() as LinkedHashSet<IM>

        if (mergeDuplicate) {
            duplicateContacts.forEach {
                IMs.addAll(it.IMs)
            }
        }

        IMs = IMs.sortedBy { it.type }.toMutableSet() as LinkedHashSet<IM>
        fullContact!!.IMs = IMs.toMutableList() as ArrayList<IM>
        binding.contactImsHolder.removeAllViews()

        if (IMs.isNotEmpty() && showFields and SHOW_IMS_FIELD != 0) {
            IMs.forEach {
                ItemViewImBinding.inflate(layoutInflater, binding.contactImsHolder, false).apply {
                    val IM = it
                    binding.contactImsHolder.addView(root)
                    contactIm.text = IM.value
                    contactImType.text = getIMTypeText(IM.type, IM.label)
                    root.copyOnLongClick(IM.value)
                }
            }
            binding.contactImsImage.beVisible()
            binding.contactImsHolder.beVisible()
        } else {
            binding.contactImsImage.beGone()
            binding.contactImsHolder.beGone()
        }
    }

    private fun setupEvents() {
        var events = contact!!.events.toMutableSet() as LinkedHashSet<Event>

        if (mergeDuplicate) {
            duplicateContacts.forEach {
                events.addAll(it.events)
            }
        }

        events = events.sortedBy { it.type }.toMutableSet() as LinkedHashSet<Event>
        fullContact!!.events = events.toMutableList() as ArrayList<Event>
        binding.contactEventsHolder.removeAllViews()

        if (events.isNotEmpty() && showFields and SHOW_EVENTS_FIELD != 0) {
            events.forEach {
                ItemViewEventBinding.inflate(layoutInflater, binding.contactEventsHolder, false).apply {
                    binding.contactEventsHolder.addView(root)
                    it.value.getDateTimeFromDateString(true, contactEvent)
                    contactEventType.setText(getEventTextId(it.type))
                    root.copyOnLongClick(it.value)
                }
            }
            binding.contactEventsImage.beVisible()
            binding.contactEventsHolder.beVisible()
        } else {
            binding.contactEventsImage.beGone()
            binding.contactEventsHolder.beGone()
        }
    }

    private fun setupWebsites() {
        var websites = contact!!.websites.toMutableSet() as LinkedHashSet<String>

        if (mergeDuplicate) {
            duplicateContacts.forEach {
                websites.addAll(it.websites)
            }
        }

        websites = websites.sorted().toMutableSet() as LinkedHashSet<String>
        fullContact!!.websites = websites.toMutableList() as ArrayList<String>
        binding.contactWebsitesHolder.removeAllViews()

        if (websites.isNotEmpty() && showFields and SHOW_WEBSITES_FIELD != 0) {
            websites.forEach {
                val url = it
                ItemWebsiteBinding.inflate(layoutInflater, binding.contactWebsitesHolder, false).apply {
                    binding.contactWebsitesHolder.addView(root)
                    contactWebsite.text = url
                    root.copyOnLongClick(url)

                    root.setOnClickListener {
                        openWebsiteIntent(url)
                    }
                }
            }
            binding.contactWebsitesImage.beVisible()
            binding.contactWebsitesHolder.beVisible()
        } else {
            binding.contactWebsitesImage.beGone()
            binding.contactWebsitesHolder.beGone()
        }
    }

    private fun setupGroups() {
        var groups = contact!!.groups.toMutableSet() as LinkedHashSet<Group>

        if (mergeDuplicate) {
            duplicateContacts.forEach {
                groups.addAll(it.groups)
            }
        }

        groups = groups.sortedBy { it.title }.toMutableSet() as LinkedHashSet<Group>
        fullContact!!.groups = groups.toMutableList() as ArrayList<Group>
        binding.contactGroupsHolder.removeAllViews()

        if (groups.isNotEmpty() && showFields and SHOW_GROUPS_FIELD != 0) {
            groups.forEach {
                ItemViewGroupBinding.inflate(layoutInflater, binding.contactGroupsHolder, false).apply {
                    val group = it
                    binding.contactGroupsHolder.addView(root)
                    contactGroup.text = group.title
                    root.copyOnLongClick(group.title)
                }
            }
            binding.contactGroupsImage.beVisible()
            binding.contactGroupsHolder.beVisible()
        } else {
            binding.contactGroupsImage.beGone()
            binding.contactGroupsHolder.beGone()
        }
    }

    private fun setupContactSources() {
        val showSources = showFields and SHOW_CONTACT_SOURCE_FIELD != 0
        val sources = listOf(contact!!) + if (mergeDuplicate) duplicateContacts else emptyList()
        binding.contactSourcesHolder.removeAllViews()
        binding.contactLinkedAppRows.removeAllViews()
        val linkedApps = linkedMapOf<String, Pair<String, MutableList<Int>>>()

        sources.map { it to getPublicContactSourceSync(it.source, contactSources) }
            .sortedBy { (_, source) -> source.lowercase(Locale.getDefault()) }
            .forEach { (entry, source) ->
                val packageName = linkedContactAppPackage(entry.source, source, contactSources)
                if (packageName != null) {
                    linkedApps.getOrPut(packageName) { source to mutableListOf() }.second.add(entry.id)
                } else if (showSources) {
                    val sourceRow = layoutInflater.inflate(R.layout.item_view_storage_source, binding.contactSourcesHolder, false) as TextView
                    sourceRow.apply {
                        val label = contactSourceLabel(source)
                        text = label
                        copyOnLongClick(label)
                        setOnClickListener { launchEditContact(entry, true) }
                    }
                    binding.contactSourcesHolder.addView(sourceRow)
                }
            }

        linkedApps.forEach { (packageName, app) ->
            val (name, contactIds) = app
            val icon = getPackageDrawable(packageName) ?: return@forEach
            ItemViewContactSourceBinding.inflate(layoutInflater, binding.contactLinkedAppRows, false).apply {
                contactSource.text = name
                contactSourceImage.setImageDrawable(icon)
                contactSourceImage.beVisible()
                root.setOnClickListener { showSocialActions(contactIds, packageName) }
                contactSource.setOnClickListener { showSocialActions(contactIds, packageName) }
                binding.contactLinkedAppRows.addView(root)
            }
        }

        binding.contactSources.isVisible = binding.contactSourcesHolder.childCount > 0
        binding.contactSources.background = contactCardBackground()
        binding.contactSourcesIcon.applyColorFilter(getProperTextColor())
        binding.contactLinkedApps.isVisible = binding.contactLinkedAppRows.childCount > 0
        binding.contactLinkedApps.background = contactCardBackground()
    }

    private fun setupNotes() {
        val notes = contact!!.notes
        if (notes.isNotEmpty() && showFields and SHOW_NOTES_FIELD != 0) {
            binding.contactNotes.text = notes
            binding.contactNotesImage.beVisible()
            binding.contactNotes.beVisible()
            binding.contactNotes.copyOnLongClick(notes)
        } else {
            binding.contactNotesImage.beGone()
            binding.contactNotes.beGone()
        }
    }

    private fun setupRingtone() {
        if (showFields and SHOW_RINGTONE_FIELD != 0) {
            binding.contactRingtoneImage.beVisible()
            binding.contactRingtone.beVisible()

            val ringtone = contact!!.ringtone
            if (ringtone?.isEmpty() == true) {
                binding.contactRingtone.text = getString(org.fossify.commons.R.string.no_sound)
            } else if (ringtone?.isNotEmpty() == true && ringtone != getDefaultRingtoneUri().toString()) {
                if (ringtone == SILENT) {
                    binding.contactRingtone.text = getString(org.fossify.commons.R.string.no_sound)
                } else {
                    systemRingtoneSelected(Uri.parse(ringtone))
                }
            } else {
                binding.contactRingtoneImage.beGone()
                binding.contactRingtone.beGone()
                return
            }

            binding.contactRingtone.copyOnLongClick(binding.contactRingtone.text.toString())

            binding.contactRingtone.setOnClickListener {
                val ringtonePickerIntent = getRingtonePickerIntent()
                try {
                    startActivityForResult(ringtonePickerIntent, INTENT_SELECT_RINGTONE)
                } catch (e: Exception) {
                    val currentRingtone = contact!!.ringtone ?: getDefaultAlarmSound(RingtoneManager.TYPE_RINGTONE).uri
                    SelectAlarmSoundDialog(this@ViewContactActivity,
                        currentRingtone,
                        AudioManager.STREAM_RING,
                        PICK_RINGTONE_INTENT_ID,
                        RingtoneManager.TYPE_RINGTONE,
                        true,
                        onAlarmPicked = {
                            binding.contactRingtone.text = it?.title
                            ringtoneUpdated(it?.uri)
                        },
                        onAlarmSoundDeleted = {}
                    )
                }
            }
        } else {
            binding.contactRingtoneImage.beGone()
            binding.contactRingtone.beGone()
        }
    }

    private fun setupOrganization() {
        val organization = contact!!.organization
        if (organization.isNotEmpty() && showFields and SHOW_ORGANIZATION_FIELD != 0) {
            binding.contactOrganizationCompany.text = organization.company
            binding.contactOrganizationJobPosition.text = organization.jobPosition
            binding.contactOrganizationImage.beGoneIf(organization.isEmpty())
            binding.contactOrganizationCompany.beGoneIf(organization.company.isEmpty())
            binding.contactOrganizationJobPosition.beGoneIf(organization.jobPosition.isEmpty())
            binding.contactOrganizationCompany.copyOnLongClick(binding.contactOrganizationCompany.value)
            binding.contactOrganizationJobPosition.copyOnLongClick(binding.contactOrganizationJobPosition.value)

            if (organization.company.isEmpty() && organization.jobPosition.isNotEmpty()) {
                (binding.contactOrganizationImage.layoutParams as RelativeLayout.LayoutParams).addRule(
                    RelativeLayout.ALIGN_TOP,
                    binding.contactOrganizationJobPosition.id
                )
            }
        } else {
            binding.contactOrganizationImage.beGone()
            binding.contactOrganizationCompany.beGone()
            binding.contactOrganizationJobPosition.beGone()
        }
    }

    private fun showSocialActions(contactIds: List<Int>, packageName: String) {
        ensureBackgroundThread {
            val actions = contactIds.distinct().flatMap { getSocialActions(it) }
                .filter { it.packageName == packageName }.distinctBy { it.dataId }
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    if (actions.isEmpty()) {
                        toast(R.string.no_linked_app_actions)
                        return@runOnUiThread
                    }
                    ChooseSocialDialog(this@ViewContactActivity, ArrayList(actions)) { action ->
                        Intent(Intent.ACTION_VIEW).apply {
                            val uri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, action.dataId)
                            setDataAndType(uri, action.mimetype)
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TASK
                            try {
                                startActivity(this)
                            } catch (e: SecurityException) {
                                handlePermission(PERMISSION_CALL_PHONE) { success ->
                                    if (success) {
                                        startActivity(this)
                                    } else {
                                        toast(org.fossify.commons.R.string.no_phone_call_permission)
                                    }
                                }
                            } catch (e: ActivityNotFoundException) {
                                toast(org.fossify.commons.R.string.no_app_found)
                            } catch (e: Exception) {
                                showErrorToast(e)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun customRingtoneSelected(ringtonePath: String) {
        binding.contactRingtone.text = ringtonePath.getFilenameFromPath()
        ringtoneUpdated(ringtonePath)
    }

    override fun systemRingtoneSelected(uri: Uri?) {
        val contactRingtone = RingtoneManager.getRingtone(this, uri)
        binding.contactRingtone.text = contactRingtone.getTitle(this)
        ringtoneUpdated(uri?.toString() ?: "")
    }

    private fun ringtoneUpdated(path: String?) {
        contact!!.ringtone = path

        ensureBackgroundThread {
            if (contact!!.isPrivate()) {
                LocalContactsHelper(this).updateRingtone(contact!!.contactId, path ?: "")
            } else {
                ContactsHelper(this).updateRingtone(contact!!.contactId.toString(), path ?: "")
            }
        }
    }

    private fun initializeDuplicateContacts(callback: () -> Unit) {
        getDuplicateContacts(contact!!, false) {
            duplicateContacts = it
            runOnUiThread {
                callback()
            }
        }
    }

    private fun deleteContactWithMergeLogic() {
        val addition = if (mergeDuplicate && duplicateContacts.isNotEmpty()) {
            "\n\n${getString(R.string.delete_from_all_sources)}"
        } else {
            ""
        }

        val message = "${getString(org.fossify.commons.R.string.proceed_with_deletion)}$addition"
        ConfirmationDialog(this, message) {
            if (contact != null) {
                ContactsHelper(this).deleteContact(contact!!, mergeDuplicate) {
                    finish()
                }
            }
        }
    }

    private fun getStarDrawable(on: Boolean) =
        resources.getDrawable(if (on) org.fossify.commons.R.drawable.ic_star_vector else org.fossify.commons.R.drawable.ic_star_outline_vector)

    private fun hideBigContactPhoto() {
        binding.contactPhotoBig.animate().alpha(0f).withEndAction { binding.contactPhotoBig.beGone() }.start()
    }

    private fun View.copyOnLongClick(value: String) {
        setOnLongClickListener {
            copyToClipboard(value)
            true
        }
    }
}
