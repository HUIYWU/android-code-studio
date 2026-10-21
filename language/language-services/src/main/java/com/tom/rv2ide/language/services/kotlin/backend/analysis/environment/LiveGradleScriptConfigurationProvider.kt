package com.tom.rv2ide.language.services.kotlin.backend.analysis.environment

import com.tom.rv2ide.tooling.api.models.GradleKotlinDslModel
import com.tom.rv2ide.tooling.api.models.GradleKotlinDslModelState
import org.jetbrains.kotlin.scripting.definitions.K1SpecificScriptingServiceAccessor
import org.jetbrains.kotlin.scripting.definitions.ScriptConfigurationsProvider
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinitionProvider
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationResult
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationWrapper
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import java.nio.file.Path
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.asSuccess

@Suppress("DEPRECATION")
internal class LiveGradleScriptDefinitionProvider(
    model: GradleKotlinDslModel,
) : ScriptDefinitionProvider {
    private val defaultDefinition = ScriptDefinition.getDefault(
        kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration,
    )
    private val definitionsByPath: Map<Path, ScriptDefinition> = buildDefinitions(model)

    override val currentDefinitions: Sequence<ScriptDefinition>
        get() = definitionsByPath.values.asSequence() + sequenceOf(defaultDefinition)

    override fun isScript(script: SourceCode): Boolean =
        findDefinition(script) != null || defaultDefinition.isScript(script)

    override fun findDefinition(script: SourceCode): ScriptDefinition? = script.locationId
        ?.let { runCatching { definitionsByPath[Path.of(it).normalize()] }.getOrNull() }

    override fun getDefaultDefinition(): ScriptDefinition = defaultDefinition

    override fun getKnownFilenameExtensions(): Sequence<String> = sequenceOf("kts")

    private fun buildDefinitions(model: GradleKotlinDslModel): Map<Path, ScriptDefinition> {
        if (model.schemaVersion != 1 || model.state !in VALID_STATES) return emptyMap()

        return model.builds
            .asSequence()
            .filter { it.state in VALID_STATES }
            .flatMap { build ->
                build.scripts.asSequence()
                    .filter { it.state in VALID_STATES }
                    .map { Path.of(it.scriptPath).normalize() to it }
            }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (path, candidates) ->
                candidates.singleOrNull()?.let { path to createDefinition(it.implicitImports) }
            }
            .toMap()
    }

    private fun createDefinition(imports: List<String>): ScriptDefinition {
        val compilationConfiguration = ScriptCompilationConfiguration(defaultDefinition.compilationConfiguration) {
            defaultImports(imports)
        }
        return ScriptDefinition.FromConfigurations(
            defaultDefinition.hostConfiguration,
            compilationConfiguration,
            defaultDefinition.evaluationConfiguration,
        )
    }

    private companion object {
        val VALID_STATES = setOf(
            GradleKotlinDslModelState.VALID,
            GradleKotlinDslModelState.PARTIAL,
        )
    }
}

@Suppress("DEPRECATION")
@OptIn(K1SpecificScriptingServiceAccessor::class)
internal class LiveGradleScriptConfigurationsProvider(
    project: Project,
    private val definitions: ScriptDefinitionProvider,
) : ScriptConfigurationsProvider(project) {
    override fun getScriptCompilationConfiguration(
        scriptSource: SourceCode,
        providedConfiguration: ScriptCompilationConfiguration?,
    ): ScriptCompilationConfigurationResult? {
        val definition = definitions.findDefinition(scriptSource) ?: return null
        val configuration = ScriptCompilationConfiguration(providedConfiguration ?: definition.compilationConfiguration) {
            defaultImports(definition.compilationConfiguration[defaultImports].orEmpty())
        }
        return ScriptCompilationConfigurationWrapper(scriptSource, configuration).asSuccess()
    }
}
