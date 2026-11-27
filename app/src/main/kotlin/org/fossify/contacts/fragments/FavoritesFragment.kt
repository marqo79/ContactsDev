package org.fossify.contacts.fragments

import android.content.Context
import android.util.AttributeSet
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beGone
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.TAB_FAVORITES
import org.fossify.commons.models.contacts.Contact
import org.fossify.contacts.activities.MainActivity
import org.fossify.contacts.activities.SimpleActivity
import org.fossify.contacts.adapters.ContactsAdapter
import org.fossify.contacts.databinding.FragmentFavoritesBinding
import org.fossify.contacts.databinding.FragmentLettersLayoutBinding
import org.fossify.contacts.dialogs.SelectContactsDialog
import org.fossify.contacts.extensions.config
import org.fossify.contacts.extensions.viewContact
import org.fossify.contacts.helpers.LOCATION_FAVORITES_TAB
import org.fossify.contacts.interfaces.RefreshContactsListener

class FavoritesFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment<MyViewPagerFragment.LetterLayout>(context, attributeSet) {
    private lateinit var binding: FragmentFavoritesBinding
    private lateinit var sectionDecoration: ContactSectionDecoration

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentFavoritesBinding.bind(this)
        innerBinding = LetterLayout(FragmentLettersLayoutBinding.bind(binding.root))
        sectionDecoration = ContactSectionDecoration(innerBinding.fragmentList, hasLetterIndex = false)
        innerBinding.fragmentList.addItemDecoration(sectionDecoration)
        innerBinding.letterFastscroller.beGone()
        innerBinding.letterFastscrollerThumb.beGone()
    }

    override fun fabClicked() {
        finishActMode()
        showAddFavoritesDialog()
    }

    override fun placeholderClicked() {
        showAddFavoritesDialog()
    }

    private fun getRecyclerAdapter() = innerBinding.fragmentList.adapter as? ContactsAdapter

    private fun showAddFavoritesDialog() {
        SelectContactsDialog(activity!!, allContacts, true, false) { addedContacts, removedContacts ->
            ContactsHelper(activity as SimpleActivity).apply {
                addFavorites(addedContacts)
                removeFavorites(removedContacts)
            }

            (activity as? MainActivity)?.refreshContacts(TAB_FAVORITES)
        }
    }

    fun setupContactsFavoritesAdapter(contacts: List<Contact>) {
        setupViewVisibility(contacts.isNotEmpty())
        sectionDecoration.applyListBackground(innerBinding.fragmentList)
        val currAdapter = getRecyclerAdapter()

        if (currAdapter == null || forceListRedraw) {
            forceListRedraw = false
            val location = LOCATION_FAVORITES_TAB

            ContactsAdapter(
                activity = activity as SimpleActivity,
                contactItems = contacts.toMutableList(),
                refreshListener = activity as RefreshContactsListener,
                location = location,
                removeListener = null,
                recyclerView = innerBinding.fragmentList,
                itemClick = {
                    (activity as RefreshContactsListener).contactClicked(it as Contact)
                },
                profileIconClick = {
                    activity?.viewContact(it as Contact)
                }
            ).apply {
                innerBinding.fragmentList.adapter = this
            }

            if (context.areSystemAnimationsEnabled) {
                innerBinding.fragmentList.scheduleLayoutAnimation()
            }
        } else {
            currAdapter.apply {
                startNameWithSurname = context.config.startNameWithSurname
                showPhoneNumbers = context.config.showPhoneNumbers
                showContactThumbnails = context.config.showContactThumbnails
                updateItems(contacts)
            }
        }
    }

    internal fun updateFavoriteSection(isVisible: Boolean) {
        sectionDecoration.sectionTitles = if (isVisible) mapOf(0 to FAVORITES_SECTION_TITLE) else emptyMap()
        innerBinding.fragmentList.invalidateItemDecorations()
    }
}
