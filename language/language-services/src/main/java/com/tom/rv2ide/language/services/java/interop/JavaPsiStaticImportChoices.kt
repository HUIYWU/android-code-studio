package com.tom.rv2ide.language.services.java.interop

data class JavaPsiStaticImportChoices(
    @JvmField val simpleName: String,
    @JvmField val isMethod: Boolean,
    @JvmField val qualifiedNames: List<String>,
    @JvmField val key: String = simpleName,
)