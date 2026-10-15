package com.tom.rv2ide.language.services.kotlin.backend.analysis.input

import com.tom.rv2ide.projects.models.ProjectInputSnapshot

internal data class KotlinProjectInputSnapshotState(
    val snapshot: ProjectInputSnapshot,
    val fingerprints: KotlinProjectInputFingerprints,
) {
  companion object {
    fun from(snapshot: ProjectInputSnapshot): KotlinProjectInputSnapshotState =
        KotlinProjectInputSnapshotState(
            snapshot = snapshot,
            fingerprints = KotlinProjectInputFingerprint.of(snapshot),
        )
  }
}
