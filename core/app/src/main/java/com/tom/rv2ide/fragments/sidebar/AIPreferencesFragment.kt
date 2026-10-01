package com.tom.rv2ide.fragments.sidebar

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.catalog.ModelSources
import com.tom.rv2ide.artificial.dialogs.ProviderSwitchDialog
import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.managers.CodeCompletionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class AIPreferencesFragment : Fragment() {

    companion object {
        private val log = LoggerFactory.getLogger(AIPreferencesFragment::class.java)
    }

    private val sharedViewModel: AISharedViewModel by activityViewModels()
    private val aiAgent: AIAgentManager get() = sharedViewModel.aiAgent
    private val agents: Agents get() = sharedViewModel.agents

    private val codeCompletionManager: CodeCompletionManager?
        get() = runCatching {
            CodeCompletionManager.getInstance(requireContext(), lifecycleScope, aiAgent)
        }.getOrNull()

    private lateinit var providerDropdown: AutoCompleteTextView
    private lateinit var modelDropdown: AutoCompleteTextView
    private lateinit var autoSwitchToggle: MaterialSwitch
    private lateinit var codeCompletionToggle: MaterialSwitch
    private lateinit var contextLimitSlider: Slider
    private lateinit var currentProviderText: MaterialTextView
    private lateinit var currentModelText: MaterialTextView
    
    private val providerSwitchDialog by lazy { ProviderSwitchDialog(requireContext()) }
    
    private var isCompletionEnabled = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_ai_preferences, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initializeViews(view)
        setupProviderDropdown()
        setupModelDropdown()
        setupToggles()
        setupContextLimitSlider()
        updateCurrentStatus()
    }

    override fun onResume() {
        super.onResume()
        updateCurrentStatus()
        updateProviderDropdownSelection()
        updateModelDropdown()
        syncCodeCompletionToggle()
        syncContextLimitSlider()
    }

    private fun initializeViews(view: View) {
        providerDropdown = view.findViewById(R.id.providerDropdown)
        modelDropdown = view.findViewById(R.id.modelDropdown)
        autoSwitchToggle = view.findViewById(R.id.autoSwitchToggle)
        codeCompletionToggle = view.findViewById(R.id.codeCompletionToggle)
        contextLimitSlider = view.findViewById(R.id.contextLimitSlider)
        currentProviderText = view.findViewById(R.id.currentProviderText)
        currentModelText = view.findViewById(R.id.currentModelText)
    }

    private fun setupProviderDropdown() {
        // Single source of provider ids; the catalogue owns the canonical order.
        val allProviderIds = ModelSources.PROVIDER_IDS
        val providerNames = allProviderIds.map { ModelSources.providerName(it) }

        val adapter = ArrayAdapter(requireContext(), R.layout.item_dropdown_single_line, providerNames)
        providerDropdown.threshold = Int.MAX_VALUE
        providerDropdown.setAdapter(adapter)
        providerDropdown.setOnClickListener { providerDropdown.showDropDown() }
        
        updateProviderDropdownSelection()
        
        providerDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedProviderId = allProviderIds[position]
            val selectedProviderName = providerNames[position]

            // Local LLM is configured on the AI Agent preferences page, which owns the endpoint and
            // the model list. Picking it here only switches to it; there is nothing to fill in.
            handleProviderChange(selectedProviderId, selectedProviderName)
        }
    }
    
    
    private fun updateProviderDropdownSelection() {
        val currentProviderId = agents.getProvider()
        providerDropdown.setText(ModelSources.providerName(currentProviderId), false)
    }

    private fun updateCurrentStatus() {
        val currentProvider = agents.getProvider()
        val currentModel = agents.getAgent()
        
        if (IdeLogConfig.shouldLogDebug()) {
            log.debug("Current provider: {}, model: {}", currentProvider, currentModel)
        }

        currentProviderText.text = ModelSources.providerName(currentProvider)
        currentModelText.text = currentModel
    }

    private fun setupModelDropdown() {
        updateModelDropdown()
        
        modelDropdown.setOnItemClickListener { _, _, position, _ ->
            val currentProvider = agents.getProvider()
            val models = agents.getModelsForProvider(currentProvider)
            
            if (position < models.size) {
                val selectedModel = models[position]
                handleModelChange(selectedModel)
            }
        }
    }

    private fun updateModelDropdown() {
        val currentProvider = agents.getProvider()
        val models = agents.getModelsForProvider(currentProvider)
        
        val adapter = ArrayAdapter(requireContext(), R.layout.item_dropdown_single_line, models)
        modelDropdown.threshold = Int.MAX_VALUE
        modelDropdown.setAdapter(adapter)
        modelDropdown.setOnClickListener { modelDropdown.showDropDown() }
        
        val currentModel = agents.getAgent()
        // The stored model takes precedence over the first catalogue entry. Substituting another
        // name here would display a model that is not the configured one — which is exactly what
        // happened for Local LLM, whose placeholder name used to overwrite the real selection.
        if (currentModel.isNotBlank()) {
            modelDropdown.setText(currentModel, false)
        } else if (models.isNotEmpty()) {
            modelDropdown.setText(models.first(), false)
        }
    }

    private fun setupToggles() {
        autoSwitchToggle.isChecked = providerSwitchDialog.isAutoSwitchEnabled()
        autoSwitchToggle.setOnCheckedChangeListener { _, isChecked ->
            providerSwitchDialog.setAutoSwitch(isChecked)
            val message = if (isChecked) {
                "Auto-switch enabled"
            } else {
                "Auto-switch disabled"
            }
            showSnackbar(message)
        }
        
        val savedState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
            .getBoolean("code_completion_enabled", true)
        isCompletionEnabled = savedState
        codeCompletionToggle.isChecked = savedState
        
        codeCompletionToggle.setOnCheckedChangeListener { _, isChecked ->
            if (IdeLogConfig.shouldLogDebug()) {
                log.debug("Toggle changed to: {}", isChecked)
            }
            
            isCompletionEnabled = isChecked
            
            requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("code_completion_enabled", isChecked)
                .apply()
            
            lifecycleScope.launch {
                applyCompletionStateChange(isChecked)
            }
            
            val message = if (isChecked) {
                "✅ Code completion enabled"
            } else {
                "❌ Code completion disabled"
            }
            showSnackbar(message)
        }
    }
    
    private suspend fun applyCompletionStateChange(enabled: Boolean) {
        if (IdeLogConfig.shouldLogDebug()) {
            log.debug("Applying completion state change: {}", enabled)
        }
        
        if (enabled) {
            codeCompletionManager?.reattachToCurrentEditor()
            if (IdeLogConfig.shouldLogDebug()) {
                log.debug("Re-enabled code completion")
            }
        } else {
            codeCompletionManager?.cleanup()
            if (IdeLogConfig.shouldLogDebug()) {
                log.debug("Disabled code completion")
            }
        }
    }

    private fun syncCodeCompletionToggle() {
        val savedState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
            .getBoolean("code_completion_enabled", true)
        
        if (IdeLogConfig.shouldLogDebug()) {
            log.debug("Syncing toggle: saved={}", savedState)
        }

        isCompletionEnabled = savedState
        codeCompletionToggle.isChecked = savedState
    }

    /**
     * Wires the compression threshold.
     *
     * This is the same control the context panel offers, and both write the one stored value; the
     * running manager is told so that its usage ring reflects the new limit straight away.
     */
    private fun setupContextLimitSlider() {
        contextLimitSlider.valueFrom = Agents.CONTEXT_CHAR_LIMIT_MIN.toFloat()
        contextLimitSlider.valueTo = Agents.CONTEXT_CHAR_LIMIT_MAX.toFloat()
        contextLimitSlider.stepSize = Agents.CONTEXT_CHAR_LIMIT_STEP.toFloat()
        contextLimitSlider.value = agents.getContextCharLimit().toFloat()
        contextLimitSlider.setLabelFormatter { value -> "${value.toInt() / 1000}K" }
        contextLimitSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            agents.setContextCharLimit(value.toInt())
            aiAgent.refreshContextUsage()
        }
    }

    /** Re-reads the stored threshold: the context panel can change it behind this page's back. */
    private fun syncContextLimitSlider() {
        val limit = agents.getContextCharLimit().toFloat()
        if (contextLimitSlider.value != limit) {
            contextLimitSlider.value = limit
        }
    }

    private fun handleProviderChange(providerId: String, providerName: String) {
        if (IdeLogConfig.shouldLogDebug()) {
            log.debug("Switching to provider: {}", providerId)
        }
        val models = agents.getModelsForProvider(providerId)
        // Model lists are never empty: the repository falls back to the built-in catalogue.
        //
        // The model already chosen for this provider wins over its default: models are stored per
        // provider, so switching back to one should restore the selection made for it rather than
        // reset it. `Agents` resolves to the right entry for every provider, Local LLM included.
        val modelForProvider =
            agents.getModel(providerId) ?: agents.getDefaultModelForProvider(providerId)
        agents.setModel(providerId, modelForProvider)
        if (IdeLogConfig.shouldLogDebug()) {
            log.debug("Available models for {}: {}", providerId, models.joinToString())
            log.debug("Set default model: {}", modelForProvider)
        }
        
        
        agents.setProvider(providerId)
        
        updateModelDropdown()
        
        if (aiAgent.setProvider(providerId)) {
            aiAgent.reinitializeWithSelectedModel()
            updateCurrentStatus()
            
            lifecycleScope.launch {
                if (isCompletionEnabled) {
                    delay(500)
                    codeCompletionManager?.reattachToCurrentEditor()
                    if (IdeLogConfig.shouldLogDebug()) {
                        log.debug("Reattached completion after provider change")
                    }
                }
            }
            
            showSnackbar("Switched to $providerName")
        } else {
            showSnackbar("⚠️ No valid API key for $providerName")
        }
    }

    private fun handleModelChange(modelName: String) {
        if (IdeLogConfig.shouldLogDebug()) {
            log.debug("Switching to model: {}", modelName)
        }
        agents.setModel(agents.getProvider(), modelName)
        aiAgent.reinitializeWithSelectedModel()
        updateCurrentStatus()
        
        lifecycleScope.launch {
            if (isCompletionEnabled) {
                delay(500)
                codeCompletionManager?.reattachToCurrentEditor()
                if (IdeLogConfig.shouldLogDebug()) {
                    log.debug("Reattached completion after model change")
                }
            }
        }
        
        showSnackbar("Model switched to: $modelName")
    }

    private fun showSnackbar(message: String) {
        view?.let {
            Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
    }
}
