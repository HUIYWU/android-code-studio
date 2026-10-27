package com.tom.rv2ide.language.services.kotlin.backend.analysis.environment

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaModuleBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibrarySourceModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.StandaloneProjectFactory
import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.platform.TargetPlatform
import java.nio.file.Path

@OptIn(KaExperimentalApi::class, KaPlatformInterface::class)
internal data class LiveLibraryModulePair(
    val binary: KaLibraryModule,
    val source: KaLibrarySourceModule?,
)

@OptIn(KaExperimentalApi::class, KaPlatformInterface::class)
internal data class LiveScriptLibraryModulePair(
    val binary: KaLibraryModule,
    val source: KaLibrarySourceModule?,
)

@OptIn(KaExperimentalApi::class, KaPlatformInterface::class)
internal fun createLiveLibraryModulePair(
    coreApplicationEnvironment: CoreApplicationEnvironment,
    project: Project,
    platform: TargetPlatform,
    libraryName: String,
    binaryRoots: Collection<Path>,
    sourceFiles: Collection<VirtualFile>,
    directDependencies: List<KaModule> = emptyList(),
): LiveLibraryModulePair {
    val binaryScope = StandaloneProjectFactory.createLibraryModuleSearchScope(
        binaryRoots,
        emptyList(),
        coreApplicationEnvironment,
        project,
    )
    val sourceScope = if (sourceFiles.isEmpty()) {
        GlobalSearchScope.EMPTY_SCOPE
    } else {
        GlobalSearchScope.filesScope(project, sourceFiles)
    }
    lateinit var binary: LiveBinaryLibraryModule
    val source = if (sourceFiles.isEmpty()) {
        null
    } else {
        LiveSourceLibraryModule(
            project = project,
            targetPlatform = platform,
            libraryName = "$libraryName:sources",
            binaryLibraryProvider = { binary },
            baseContentScope = sourceScope,
            directDependencies = directDependencies,
        )
    }
    binary = LiveBinaryLibraryModule(
        project = project,
        targetPlatform = platform,
        libraryName = libraryName,
        binaryRoots = binaryRoots,
        baseContentScope = binaryScope,
        librarySourcesProvider = { source },
        directDependencies = directDependencies,
    )
    return LiveLibraryModulePair(binary, source)
}

@OptIn(KaExperimentalApi::class, KaPlatformInterface::class)
internal fun createLiveScriptLibraryModulePair(
    coreApplicationEnvironment: CoreApplicationEnvironment,
    project: Project,
    platform: TargetPlatform,
    libraryName: String,
    binaryRoots: Collection<Path>,
    sourceFiles: Collection<VirtualFile>,
    directDependencies: List<KaModule> = emptyList(),
): LiveScriptLibraryModulePair {
    val pair = createLiveLibraryModulePair(
        coreApplicationEnvironment = coreApplicationEnvironment,
        project = project,
        platform = platform,
        libraryName = libraryName,
        binaryRoots = binaryRoots,
        sourceFiles = sourceFiles,
        directDependencies = directDependencies,
    )
    return LiveScriptLibraryModulePair(pair.binary, pair.source)
}

@OptIn(KaExperimentalApi::class, KaPlatformInterface::class)
private class LiveBinaryLibraryModule(
    override val project: Project,
    override val targetPlatform: TargetPlatform,
    override val libraryName: String,
    override val binaryRoots: Collection<Path>,
    override val baseContentScope: GlobalSearchScope,
    private val librarySourcesProvider: () -> KaLibrarySourceModule?,
    private val directDependencies: List<KaModule>,
) : KaModuleBase(), KaLibraryModule {
    override val directRegularDependencies: List<KaModule> = directDependencies
    override val directDependsOnDependencies: List<KaModule> = emptyList()
    override val directFriendDependencies: List<KaModule> = emptyList()
    override val binaryVirtualFiles: Collection<VirtualFile> = emptyList()
    override val librarySources: KaLibrarySourceModule?
        get() = librarySourcesProvider()
    override val isSdk: Boolean = false
}

@OptIn(KaExperimentalApi::class, KaPlatformInterface::class)
private class LiveSourceLibraryModule(
    override val project: Project,
    override val targetPlatform: TargetPlatform,
    override val libraryName: String,
    private val binaryLibraryProvider: () -> KaLibraryModule,
    override val baseContentScope: GlobalSearchScope,
    private val directDependencies: List<KaModule>,
) : KaModuleBase(), KaLibrarySourceModule {
    override val directRegularDependencies: List<KaModule> = directDependencies
    override val directDependsOnDependencies: List<KaModule> = emptyList()
    override val directFriendDependencies: List<KaModule> = emptyList()
    override val binaryLibrary: KaLibraryModule
        get() = binaryLibraryProvider()
}

