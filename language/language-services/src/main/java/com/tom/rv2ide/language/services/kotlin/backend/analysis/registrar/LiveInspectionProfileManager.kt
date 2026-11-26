package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

import java.io.File
import java.util.function.Predicate
import org.jetbrains.kotlin.com.intellij.codeInspection.InspectionEP
import org.jetbrains.kotlin.com.intellij.codeInspection.deadCode.UnusedDeclarationInspectionBase
import org.jetbrains.kotlin.com.intellij.codeInspection.ex.GlobalInspectionToolWrapper
import org.jetbrains.kotlin.com.intellij.codeInspection.ex.InspectionProfileImpl
import org.jetbrains.kotlin.com.intellij.codeInspection.ex.InspectionToolsSupplier
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.components.SettingsCategory
import org.jetbrains.kotlin.com.intellij.openapi.extensions.DefaultPluginDescriptor
import org.jetbrains.kotlin.com.intellij.openapi.extensions.PluginDescriptor
import org.jetbrains.kotlin.com.intellij.openapi.extensions.PluginId
import org.jetbrains.kotlin.com.intellij.openapi.options.SchemeManager
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.profile.ProfileChangeAdapter
import org.jetbrains.kotlin.com.intellij.profile.codeInspection.BaseInspectionProfileManager
import org.jetbrains.kotlin.com.intellij.profile.codeInspection.ProjectBasedInspectionProfileManager
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier

internal class LiveInspectionProfileManager(
    override val project: Project,
) : BaseInspectionProfileManager(project.messageBus), ProjectBasedInspectionProfileManager, Disposable {
  private val unusedInspection = UnusedDeclarationInspectionBase(false).apply {
    sharedLocalInspectionTool.setParameterVisibility(PsiModifier.PRIVATE)
  }
  private val unusedExtension = InspectionEP(
      unusedInspection.javaClass.name,
      DefaultPluginDescriptor(PluginId.getId("com.intellij.java.analysis"), unusedInspection.javaClass.classLoader),
  ).apply {
    shortName = UnusedDeclarationInspectionBase.SHORT_NAME
    language = "JAVA"
    enabledByDefault = true
    level = "WARNING"
    bundle = "messages.JavaBundle"
    key = "inspection.unused.display.name"
    groupBundle = "messages.InspectionsBundle"
    groupKey = "group.names.declaration.redundancy"
    editorAttributes = "NOT_USED_ELEMENT_ATTRIBUTES"
  }
  private val tools = InspectionToolsSupplier.Simple(listOf(GlobalInspectionToolWrapper(unusedInspection, unusedExtension)))
  private val profile = object : InspectionProfileImpl("ACS Java", tools, this@LiveInspectionProfileManager, null, null) {
    override fun forceInitInspectionTools(): Boolean = true
  }
  override val schemeManager: SchemeManager<InspectionProfileImpl> = MemorySchemes(profile)

  override fun getCurrentProfile(): InspectionProfileImpl = requireNotNull(schemeManager.activeScheme)

  override fun getProfiles(): Collection<InspectionProfileImpl> = schemeManager.allSchemes

  override fun getProfile(name: String, returnRootProfileIfNamedIsAbsent: Boolean): InspectionProfileImpl? =
      schemeManager.findSchemeByName(name) ?: if (returnRootProfileIfNamedIsAbsent) getCurrentProfile() else null

  override fun setRootProfile(name: String?) {
    val selected = if (name == null) profile else requireNotNull(schemeManager.findSchemeByName(name))
    val previous = schemeManager.activeScheme
    schemeManager.setCurrent(selected, false)
    if (previous !== selected) {
      project.messageBus.syncPublisher(ProfileChangeAdapter.TOPIC).profileActivated(previous, selected)
    }
  }

  override fun fireProfileChanged(profile: InspectionProfileImpl) {
    profile.profileChanged()
    project.messageBus.syncPublisher(ProfileChangeAdapter.TOPIC).profileChanged(profile)
  }

  override fun dispose() {
    schemeManager.allSchemes.forEach { it.cleanup(project) }
    tools.dispose()
  }

  private class MemorySchemes(initial: InspectionProfileImpl) : SchemeManager<InspectionProfileImpl>() {
    private val schemes = arrayListOf(initial)
    override var activeScheme: InspectionProfileImpl? = initial
      private set
    override var currentSchemeName: String?
      get() = activeScheme?.name
      set(value) = setCurrentSchemeName(value, false)
    override val allSchemes: List<InspectionProfileImpl>
      get() = schemes.toList()
    override val allSchemeNames: Collection<String>
      get() = schemes.map { it.name }
    override val rootDirectory: File
      get() = throw UnsupportedOperationException("Analysis inspection profiles are held in memory")

    override fun loadSchemes(): Collection<InspectionProfileImpl> = allSchemes

    override fun reload(retainFilter: ((InspectionProfileImpl) -> Boolean)?) {
      if (retainFilter != null) schemes.removeAll { !retainFilter(it) }
      if (activeScheme !in schemes) activeScheme = schemes.firstOrNull()
    }

    override fun addScheme(scheme: InspectionProfileImpl, replaceExisting: Boolean) {
      val existing = schemes.indexOfFirst { it.name == scheme.name }
      if (existing < 0) schemes.add(scheme)
      else if (replaceExisting) {
        val previous = schemes.set(existing, scheme)
        if (activeScheme === previous) activeScheme = scheme
      }
    }

    override fun findSchemeByName(schemeName: String): InspectionProfileImpl? = schemes.firstOrNull { it.name == schemeName }

    override fun setCurrentSchemeName(schemeName: String?, notify: Boolean) {
      activeScheme = schemeName?.let { requireNotNull(findSchemeByName(it)) }
    }

    override fun setCurrent(scheme: InspectionProfileImpl?, notify: Boolean, processChangeSynchronously: Boolean) {
      require(scheme == null || schemes.any { it === scheme })
      activeScheme = scheme
    }

    override fun removeScheme(scheme: InspectionProfileImpl): Boolean {
      val removed = schemes.remove(scheme)
      if (activeScheme === scheme) activeScheme = schemes.firstOrNull()
      return removed
    }

    override fun removeScheme(name: String): InspectionProfileImpl? = findSchemeByName(name)?.also { removeScheme(it) }

    override fun loadBundledScheme(resourceName: String, requestor: Any?, pluginDescriptor: PluginDescriptor?): InspectionProfileImpl =
        throw UnsupportedOperationException("Analysis inspection profiles use an explicit tool list")

    override fun loadBundledSchemes(providers: Sequence<SchemeManager.LoadBundleSchemeRequest<InspectionProfileImpl>>) {
      providers.forEach { addScheme(it.createScheme(), false) }
    }

    override fun setSchemes(
        newSchemes: List<InspectionProfileImpl>,
        newCurrentScheme: InspectionProfileImpl?,
        removeCondition: Predicate<InspectionProfileImpl>?,
    ) {
      if (removeCondition == null) schemes.clear() else schemes.removeAll { removeCondition.test(it) }
      newSchemes.forEach { addScheme(it, true) }
      activeScheme = newCurrentScheme ?: activeScheme?.let { findSchemeByName(it.name) } ?: schemes.firstOrNull()
    }

    override fun isMetadataEditable(scheme: InspectionProfileImpl): Boolean = true

    override fun save() {}

    override fun getSettingsCategory(): SettingsCategory = SettingsCategory.CODE
  }
}
