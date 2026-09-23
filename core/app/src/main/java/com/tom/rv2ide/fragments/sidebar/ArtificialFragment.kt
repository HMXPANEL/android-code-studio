package com.tom.rv2ide.fragments.sidebar

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.fragments.AIChatFragment
import com.tom.rv2ide.fragments.AIHistoryFragment
import com.tom.rv2ide.ui.CodeEditorView
import com.tom.rv2ide.utils.ProjectHelper.getProjectRoot

/**
 * AI Agent screen shell.
 *
 * The second-last bottom-navigation button opens this fragment directly: it
 * shows the new AI Chat screen (header with the current project name,
 * conversation, composer) with no intermediate page. Conversation history and
 * provider settings open as overlays in [contentContainer] using the existing
 * fragments, so no history or configuration functionality is duplicated.
 */
class ArtificialFragment(
    private val editorView: CodeEditorView? = null
) : Fragment() {

    private lateinit var aiAgent: AIAgentManager
    private lateinit var agents: Agents
    private lateinit var chatContainer: View
    private lateinit var contentContainer: View
    private lateinit var projectNameText: MaterialTextView
    private lateinit var backBtn: MaterialButton
    private lateinit var moreBtn: MaterialButton

    private var savedContentContainerVisibility = View.GONE

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showMainContent()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            savedContentContainerVisibility = it.getInt(KEY_CONTENT_VISIBILITY, View.GONE)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_artificial, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        aiAgent = AIAgentManager(requireContext())
        agents = Agents(requireContext())

        chatContainer = view.findViewById(R.id.chatContainer)
        contentContainer = view.findViewById(R.id.contentContainer)
        projectNameText = view.findViewById(R.id.aiProjectName)
        backBtn = view.findViewById(R.id.aiBackBtn)
        moreBtn = view.findViewById(R.id.aiMoreBtn)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)

        projectNameText.text = resolveProjectName()

        backBtn.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        moreBtn.setOnClickListener { anchor -> showHeaderMenu(anchor) }

        if (savedInstanceState == null) {
            childFragmentManager.commit {
                replace(R.id.chatContainer, AIChatFragment.newInstance(aiAgent), TAG_CHAT)
            }
        }

        if (savedContentContainerVisibility == View.VISIBLE) {
            chatContainer.visibility = View.GONE
            contentContainer.visibility = View.VISIBLE
            backPressedCallback.isEnabled = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::contentContainer.isInitialized) {
            outState.putInt(KEY_CONTENT_VISIBILITY, contentContainer.visibility)
        }
    }

    private fun resolveProjectName(): String {
        return try {
            val name = getProjectRoot().name
            if (name.isBlank()) getString(R.string.ai_chat_no_project) else name
        } catch (e: Exception) {
            getString(R.string.ai_chat_no_project)
        }
    }

    private fun getAIChatFragment(): AIChatFragment? {
        return childFragmentManager.findFragmentByTag(TAG_CHAT) as? AIChatFragment
    }

    private fun showHeaderMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, MENU_HISTORY, 1, getString(R.string.ai_chat_menu_history))
        popup.menu.add(0, MENU_SETTINGS, 2, getString(R.string.ai_chat_menu_settings))
        popup.menu.add(0, MENU_UNDO, 3, getString(R.string.ai_chat_menu_undo))
        popup.menu.add(0, MENU_CLEAR, 4, getString(R.string.ai_chat_menu_clear))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_HISTORY -> {
                    openHistory()
                    true
                }
                MENU_SETTINGS -> {
                    openAIPreferences()
                    true
                }
                MENU_UNDO -> {
                    undoLastModification()
                    true
                }
                MENU_CLEAR -> {
                    getAIChatFragment()?.clearConversation()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun undoLastModification() {
        val success = aiAgent.undoLastModification()
        view?.let {
            Snackbar.make(
                it,
                if (success) {
                    getString(R.string.ai_chat_undo_done)
                } else {
                    getString(R.string.ai_chat_undo_empty)
                },
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun openAIPreferences() {
        val preferencesFragment = AIPreferencesFragment(
            aiAgent,
            agents,
            getAIChatFragment()?.getCodeCompletionManager()
        )
        showOverlay(preferencesFragment, "ai_preferences")
    }

    private fun openHistory() {
        showOverlay(AIHistoryFragment.newInstance(aiAgent), "ai_history")
    }

    private fun showOverlay(fragment: Fragment, backStackName: String) {
        val slideIn = AnimationUtils.loadAnimation(requireContext(), android.R.anim.slide_in_left)
        val slideOut = AnimationUtils.loadAnimation(requireContext(), android.R.anim.slide_out_right)

        chatContainer.startAnimation(slideOut)
        chatContainer.visibility = View.GONE

        contentContainer.visibility = View.VISIBLE
        contentContainer.startAnimation(slideIn)
        backPressedCallback.isEnabled = true

        childFragmentManager.commit {
            replace(R.id.contentContainer, fragment)
            addToBackStack(backStackName)
        }
    }

    private fun showMainContent() {
        val slideIn = AnimationUtils.loadAnimation(requireContext(), android.R.anim.slide_in_left)
        val slideOut = AnimationUtils.loadAnimation(requireContext(), android.R.anim.slide_out_right)

        contentContainer.startAnimation(slideOut)
        contentContainer.visibility = View.GONE

        chatContainer.visibility = View.VISIBLE
        chatContainer.startAnimation(slideIn)

        backPressedCallback.isEnabled = false

        if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStack()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::projectNameText.isInitialized) {
            projectNameText.text = resolveProjectName()
        }
    }

    override fun onDestroyView() {
        backPressedCallback.remove()
        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()
        com.tom.rv2ide.utils.EditorSidebarActions.removeFragmentFromCache("ide.editor.sidebar.ai_agent")
    }

    companion object {
        private const val TAG_CHAT = "ai_chat"
        private const val KEY_CONTENT_VISIBILITY = "content_visibility"
        private const val MENU_HISTORY = 1
        private const val MENU_SETTINGS = 2
        private const val MENU_UNDO = 3
        private const val MENU_CLEAR = 4
    }
}
